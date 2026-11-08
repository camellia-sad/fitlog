package dev.lzk.fitlog.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

enum class EquipmentType { MACHINE, DUMBBELL, BARBELL, SMITH, CABLE, BODYWEIGHT }
enum class Muscle { CHEST, BACK, SHOULDERS, QUADRICEPS, HAMSTRINGS, GLUTES, CALVES, BICEPS, TRICEPS, CORE }
enum class SessionStatus { READY, ACTIVE, PAUSED, FINISHED, DISCARDED }
enum class SetStatus { PLANNED, COMPLETED, SKIPPED }
enum class SetType { WORKING }
enum class RecordMetric(val unit:String,val inputLabel:String,val defaultMin:Int,val defaultMax:Int) {
    REPETITIONS("次","次数",8,12),SECONDS("秒","保持时长（秒）",30,60)
}

data class Exercise(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val equipmentType: EquipmentType,
    val primaryMuscles: Set<Muscle>,
    val secondaryMuscles: Set<Muscle> = emptySet(),
    val instructions: String? = null,
    val estimatedSecondsPerSet: Int? = null,
    val mediaAssetPath: String? = null,
    val isFavorite: Boolean = false,
    val isCustom: Boolean = false,
    val isArchived: Boolean = false,
    val recordMetric:RecordMetric = RecordMetric.REPETITIONS,
    val defaultWeightGrams: Long? = null,
    val bestWeightGrams: Long? = null,
    val hasManualWeight: Boolean = false,
)

data class WorkoutSession(
    val id: String,
    val startedAtUtc: Instant,
    val localWorkoutDate: LocalDate,
    val status: SessionStatus,
    val endedAtUtc: Instant? = null,
    val feedback: String? = null,
    val templateId: String? = null,
    val templateNameSnapshot: String? = null,
    val pausedAtUtc: Instant? = null,
    val pausedDurationMillis: Long = 0,
    val restEndsAtUtc: Instant? = null,
    val isExtra: Boolean = false,
    val dailyPlanDate: LocalDate? = null,
    /** A day record's slot: the template id, or "" for the day's free record; null for older standalone sessions. */
    val daySlot: String? = null,
) {
    val trainingLabel: String get() = if (isExtra) "加练" else templateNameSnapshot ?: "自由训练"
    /** The slot this session occupies or would occupy among today's records. */
    val slot: String get() = daySlot ?: templateId.orEmpty()
    /** Free and extra records have no fixed target, so they never end on their own. */
    val isFreeRecord: Boolean get() = daySlot == ""
}

data class SetRecord(
    val id: String,
    val sessionExerciseId: String,
    val setIndex: Int,
    val weightGrams: Long,
    val reps: Int,
    val status: SetStatus,
    val completedAtUtc: Instant? = null,
    val setType: SetType = SetType.WORKING,
)

data class SessionExercise(
    val id: String,
    val sessionId: String,
    val exerciseId: String,
    val sortOrder: Int,
    val exerciseNameSnapshot: String,
    val targetSetCountSnapshot: Int,
    val targetRepMinSnapshot: Int,
    val targetRepMaxSnapshot: Int,
    val sets: List<SetRecord>,
    val restSecondsSnapshot: Int? = null,
    val recordMetricSnapshot:RecordMetric = RecordMetric.REPETITIONS,
    val removedFromPlan: Boolean = false,
)

data class Workout(val session: WorkoutSession, val exercises: List<SessionExercise>) {
    val completedSetCount: Int get() = exercises.sumOf { e -> e.sets.count { it.status == SetStatus.COMPLETED } }
    val completedExerciseCount: Int get() = exercises.count { e -> e.sets.any { it.status == SetStatus.COMPLETED } }
    val plannedSetCount: Int get() = exercises.sumOf { it.sets.size }

    val allSetsCompleted: Boolean get() = plannedSetCount > 0 && completedSetCount == plannedSetCount

    fun elapsedDuration(now: Instant): Duration {
        if(session.status == SessionStatus.READY) return Duration.ZERO
        val end = session.endedAtUtc ?: now
        val elapsed = Duration.between(session.startedAtUtc, end).coerceAtLeast(Duration.ZERO)
        val currentPause = session.pausedAtUtc?.let { Duration.between(it, end).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
        return elapsed.minusMillis(session.pausedDurationMillis).minus(currentPause).coerceAtLeast(Duration.ZERO)
    }

    fun restRemainingSeconds(now: Instant): Long =
        session.restEndsAtUtc?.let {
            val remaining = Duration.between(now, it)
            if (remaining.isNegative) 0 else (remaining.seconds + if (remaining.nano > 0) 1 else 0).coerceAtMost(3600)
        } ?: 0
}

data class ExercisePerformance(
    val sessionId: String,
    val exerciseId: String,
    val startedAtUtc: Instant,
    val sets: List<SetRecord>,
)

sealed interface CompletionResult {
    val record: SetRecord
    data class Completed(override val record: SetRecord) : CompletionResult
    data class AlreadyCompleted(override val record: SetRecord) : CompletionResult
}

sealed interface SetCountResult {
    data object Updated : SetCountResult
    data class ConfirmationRequired(val completedSetIds: Set<String>, val targetCount: Int) : SetCountResult
}
