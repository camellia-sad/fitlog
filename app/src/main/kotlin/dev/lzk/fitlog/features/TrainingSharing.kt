package dev.lzk.fitlog.features

import dev.lzk.fitlog.data.RoomTrainingRepository
import dev.lzk.fitlog.data.SupabaseSocialRepository
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.UUID

/** Heartbeats live only while a screen or the existing training foreground service is active. */
@OptIn(FlowPreview::class)
class TrainingSharing(
    private val scope: CoroutineScope, private val workouts: WorkoutRepository,
    private val history: RoomTrainingRepository, private val social: SupabaseSocialRepository,
) {
    val foreground = MutableStateFlow(false)
    val trainingService = MutableStateFlow(false)
    private val device by lazy {social.deviceId()}
    private val latest = workouts.observeActiveWorkout().stateIn(scope, SharingStarted.Eagerly, null)
    private val uploaded = linkedMapOf<String, Int>()
    private var owner: String? = null
    init {
        scope.launch {
            combine(foreground, trainingService, social.profile) { front, service, profile ->
                if(front || service) profile?.id else null
            }.distinctUntilChanged().collectLatest { account ->
                if (account!=null) merge(
                    flow { while (currentCoroutineContext().isActive) {emit(Unit);delay(25_000)} },
                    combine(latest,social.selectedExercise) {workout,exercise->workout to exercise}
                        .debounce(1_500).map {Unit},
                ).conflate().collect {
                    try {
                        val current = account
                        check(social.profile.value?.id==account) {"账号已切换"}
                        if (owner != current) { uploaded.clear(); owner = current }
                        val privacy = social.sharing()
                        val workout = latest.value
                        val permitted = social.boundTrainingOwner() == current && privacy.live
                        val live = if (permitted) workout?.liveSnapshot(social.selectedTrainingExercise()) else null
                        val status = when {
                            live == null -> PresenceStatus.ONLINE
                            workout?.session?.status == SessionStatus.PAUSED -> PresenceStatus.PAUSED
                            (workout?.restRemainingSeconds(Instant.now()) ?: 0) > 0 -> PresenceStatus.REST
                            else -> PresenceStatus.TRAINING
                        }
                        social.heartbeatAs(account,device, status, live)
                        if (privacy.history && social.boundTrainingOwner() == current) {
                            for (item in history.sharingPage()) {
                                if (!isActive || social.profile.value?.id != current) break
                                val hash = item.hashCode()
                                if (uploaded[item.session.id] != hash) {
                                    social.uploadWorkoutAs(account,item); uploaded[item.session.id] = hash
                                }
                            }
                            while (uploaded.size > 200) uploaded.remove(uploaded.keys.first())
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* A failed heartbeat expires server-side; next tick retries. */ }
                }
            }
        }
    }
    suspend fun syncHistory() {
        val account = social.profile.value?.id ?: error("请先登录")
        check(social.boundTrainingOwner() == account) { "请先确认绑定本机记录" }
        var before = Long.MAX_VALUE
        var beforeId = "~"
        do {
            check(social.profile.value?.id == account && social.sharing().history) { "共享已关闭或账号已切换" }
            val page = history.sharingPage(before, beforeId)
            for (item in page) { currentCoroutineContext().ensureActive(); social.uploadWorkoutAs(account,item) }
            val last = page.lastOrNull() ?: break
            before = last.session.startedAtUtc.toEpochMilli(); beforeId = last.session.id
        } while (page.size == 50)
    }
}

internal fun Workout.liveSnapshot(selected: String?): LiveTraining {
    val exercise = exercises.find { it.id == selected } ?: exercises.firstOrNull { e -> e.sets.any { it.status != SetStatus.COMPLETED } }
        ?: exercises.lastOrNull()
    val set = exercise?.sets?.firstOrNull { it.status == SetStatus.PLANNED } ?: exercise?.sets?.lastOrNull()
    return LiveTraining(session.id, session.trainingLabel, exercise?.exerciseNameSnapshot.orEmpty(),
        set?.weightGrams ?: 0, set?.reps ?: 0, set?.setIndex ?: 0, completedSetCount, plannedSetCount, session.restEndsAtUtc,exercise?.recordMetricSnapshot ?: RecordMetric.REPETITIONS)
}
