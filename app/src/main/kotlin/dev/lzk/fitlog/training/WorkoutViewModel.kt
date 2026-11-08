package dev.lzk.fitlog.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lzk.fitlog.AppContainer
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SetRemovalRequest(val sessionExerciseId: String, val count: Int, val completedSetIds: Set<String>)

data class WorkoutUiState(
    val loading: Boolean = true,
    val exercises: List<Exercise> = emptyList(),
    val preparedExerciseIds: List<String> = emptyList(),
    val searchQuery: String = "",
    val equipment: EquipmentType? = null,
    val muscle: Muscle? = null,
    val favoritesOnly: Boolean = false,
    val archived: Boolean = false,
    val preferences: UserPreferences = UserPreferences(),
    val workout: Workout? = null,
    /** Today's current record: the one training now, else the one used last. */
    val todayWorkout: Workout? = null,
    /** All of today's records in start order. */
    val dayRecords: List<Workout> = emptyList(),
    val todayDate: java.time.LocalDate = java.time.LocalDate.now(),
    val selectedExercise: SessionExercise? = null,
    val lastPerformance: ExercisePerformance? = null,
    val summary: Workout? = null,
    val removalRequest: SetRemovalRequest? = null,
    val error: String? = null,
    /** Today's plan just finished on the workout page: the “今日计划完成” popup. */
    val planDone: Workout? = null,
    /** Today's finished plan, kept on the workout page after “返回” so it can be checked or corrected. */
    val review: Workout? = null,
    /** Sets that beat this exercise's best from before the session, with that earlier best. */
    val recordSets: Map<String, Long> = emptyMap(),
) {
    /** What the workout page shows: the running session, else the finished plan under review. */
    val shown: Workout? get() = workout ?: review

    /** Opening [slot] ("" for free) would stop a session that already holds completed sets. */
    fun switchingLosesFocus(slot: String): Boolean {
        val running = workout ?: return false
        if (running.completedSetCount == 0) return false
        return running.session.dailyPlanDate != todayDate || running.session.daySlot != slot
    }
}

private data class LibraryFilter(val query: String = "", val equipment: EquipmentType? = null, val muscle: Muscle? = null, val favorites: Boolean = false, val archived: Boolean = false)
private data class Feedback(val summary: Workout? = null, val removal: SetRemovalRequest? = null, val error: String? = null,
    val planDone: Workout? = null, val reviewing: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.workouts
    private val writes = Mutex()
    private val getExercises = GetExercisesUseCase(repository)
    private val add = AddExerciseToSessionUseCase(repository)
    private val update = UpdateSetDraftUseCase(repository)
    private val setCount = AdjustSetCountUseCase(repository)
    private val complete = CompleteSetUseCase(repository)
    private val undo = UndoCompletedSetUseCase(repository)
    private val finish = FinishWorkoutUseCase(repository)
    private val last = GetLastExercisePerformanceUseCase(repository)
    private val filters = MutableStateFlow(LibraryFilter())
    private val selection = MutableStateFlow<String?>(null)
    private val feedback = MutableStateFlow(Feedback())
    private val completions = MutableSharedFlow<SetRecord>(extraBufferCapacity = 1)

    // Completion events are emitted only for the first successful transition.
    val completedSets: SharedFlow<SetRecord> = completions.asSharedFlow()

    private val todayWorkout = flow {
        while(true) { emit(java.time.LocalDate.now()); kotlinx.coroutines.delay(15_000) }
    }.distinctUntilChanged().flatMapLatest { date -> repository.observeDayRecords(date).map { date to it } }

    val state: StateFlow<WorkoutUiState> = flow {
        container.ready.await()
        emitAll(combine(
            filters.flatMapLatest { filter ->
                getExercises(filter.query, filter.equipment, filter.muscle, filter.archived)
                    .map { filter to if (filter.favorites) it.filter { e -> e.isFavorite } else it }
            }, repository.observeActiveWorkout(), selection, feedback, container.preferences.preferences,
        ) { library, workout, selected, feedback, preferences ->
            val (filter, exercises) = library
            WorkoutUiState(
                loading = false, exercises = exercises, searchQuery = filter.query, equipment = filter.equipment,
                muscle = filter.muscle, favoritesOnly = filter.favorites, archived = filter.archived, preferences = preferences, workout = workout,
                selectedExercise = pick(workout, selected),
                summary = feedback.summary, removalRequest = feedback.removal, error = feedback.error, planDone = feedback.planDone,
            ) to (feedback.reviewing to selected)
        }.combine(todayWorkout) { (state, extra), today ->
            val (reviewing, selected) = extra
            val records = today.second
            val current = records.find { it.session.status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) }
                ?: records.maxWithOrNull(compareBy<Workout> { it.session.endedAtUtc ?: it.session.startedAtUtc }.thenBy { it.session.startedAtUtc })
            val review = records.find { state.workout == null && it.session.id == reviewing && it.session.status == SessionStatus.FINISHED }
            state.copy(todayWorkout = current, dayRecords = records, todayDate = today.first, review = review,
                selectedExercise = state.selectedExercise ?: pick(review, selected))
        }
        .combine(container.preparation.preparedExercises) { state, ids -> state.copy(preparedExerciseIds = ids) }.mapLatest { state ->
            val exercise = state.selectedExercise
            state.copy(lastPerformance = exercise?.let { last(it.exerciseId, it.sessionId) }, recordSets = records(state.shown))
        })
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(WorkoutUiState(loading = false, error = error.message ?: "读取训练失败"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUiState())

    private fun pick(workout: Workout?, selected: String?): SessionExercise? =
        workout?.exercises?.find { it.id == selected && !it.removedFromPlan }
            ?: workout?.exercises?.firstOrNull { !it.removedFromPlan && it.sets.any { set -> set.status == SetStatus.PLANNED } }
            ?: workout?.exercises?.firstOrNull { !it.removedFromPlan }

    /**
     * For each exercise, the heaviest completed set that beats the best from every other session; ties go to the first
     * such set. A first-ever session has nothing to beat, so it carries no medal.
     */
    private suspend fun records(workout: Workout?): Map<String, Long> {
        workout ?: return emptyMap()
        val result = HashMap<String, Long>()
        workout.exercises.filter { it.recordMetricSnapshot == RecordMetric.REPETITIONS }.forEach { exercise ->
            val done = exercise.sets.filter { it.status == SetStatus.COMPLETED }
            if (done.isEmpty()) return@forEach
            val before = repository.previousBestWeight(exercise.exerciseId, workout.session.id)?.takeIf { it > 0 } ?: return@forEach
            val top = done.maxOf { it.weightGrams }
            if (top > before) done.first { it.weightGrams == top }.let { result[it.id] = before }
        }
        return result
    }

    /** Edits on today's finished plan under review reopen it first (the timer resumes without the stopped stretch). */
    private suspend fun reopenIfReviewing() {
        val id = feedback.value.reviewing ?: return
        if (repository.observeActiveWorkout().first() != null) return
        repository.reopenDailyWorkout(id)
    }

    val currentSearchQuery: String get() = filters.value.query

    fun search(text: String) { filters.update { it.copy(query = text) } }
    fun equipment(value: EquipmentType?) { filters.update { it.copy(equipment = value) } }
    fun muscle(value: Muscle?) { filters.update { it.copy(muscle = value) } }
    fun favorites(value: Boolean) { filters.update { it.copy(favorites = value) } }
    fun archived(value: Boolean) { filters.update { it.copy(archived = value) } }
    fun favorite(exercise: Exercise) = mutate { container.training.setFavorite(exercise.id, !exercise.isFavorite) }
    fun archive(exercise: Exercise) = mutate { container.training.setArchived(exercise.id, !exercise.isArchived) }
    fun createCustom(draft: CustomExerciseDraft, onCreated: (String) -> Unit = {}) = mutate { onCreated(container.training.createCustomExercise(draft)) }
    fun selectExercise(id: String) { selection.value = id }
    fun clearError() { feedback.update { it.copy(error = null) } }
    fun dismissSummary() { feedback.update { it.copy(summary = null) } }
    /** “返回” on the plan-complete popup: stay on the page with the finished plan. */
    fun keepReviewing(feeling: String) = mutate {
        val done = feedback.value.planDone ?: return@mutate
        if (feeling.isNotBlank()) repository.saveFeedback(done.session.id, feeling)
        feedback.update { it.copy(planDone = null, reviewing = done.session.id) }
    }
    /** “加练” on the plan-complete popup: straight into today's extra record, without the home page in between. */
    fun extraAfterPlan(feeling: String) = mutate {
        val done = feedback.value.planDone ?: return@mutate
        if (feeling.isNotBlank()) repository.saveFeedback(done.session.id, feeling)
        open(null)
    }
    /** Leaving the workout page ends the review of a finished plan. */
    fun stopReviewing() { feedback.update { it.copy(planDone = null, reviewing = null) } }
    /** Adds the picker's exercises in order; the last one becomes the current exercise. */
    fun addExercises(ids: List<String>) = mutate { ids.forEach { addToToday(it) } }

    /** Into the running session, else today's current record, else a new free record. */
    private suspend fun addToToday(exerciseId: String) {
        val workout = repository.observeActiveWorkout().first()
        val current = repository.observeDailyWorkout(java.time.LocalDate.now()).first()
        when {
            workout != null -> selection.value = addDefault(workout.session.id, exerciseId, WorkoutRules.DEFAULT_SET_COUNT)
            current != null -> repository.addDailyExercise(current.session.id, exerciseId)
            else -> repository.startDailyWorkout(null, listOf(exerciseId))
        }
    }

    /**
     * Opens today's record for this slot: a template id, null or "" for the free record. A finished template record
     * opens for review instead of restarting.
     */
    private suspend fun open(slot: String?) {
        val opened = repository.startDailyWorkout(slot?.ifEmpty { null })
        selection.value = null
        feedback.value = Feedback(reviewing = opened.session.id.takeIf { opened.session.status == SessionStatus.FINISHED })
    }
    fun openRecord(slot: String?) = mutate { open(slot) }
    fun cancelSetRemoval() { feedback.update { it.copy(removal = null) } }

    fun startWorkout(templateId: String? = null) = openRecord(templateId)
    fun addDailySet(id: String) = mutate { repository.addDailySet(id) }
    fun removeExercise(id: String) = mutate { repository.removeExercise(id); selection.value = null }

    fun startAndAddExercise(exerciseId: String) = mutate { addToToday(exerciseId) }

    fun removePreparedExercise(id: String) = mutate {
        container.preparation.savePreparedExercises(container.preparation.preparedExercises.first().filterNot { it == id })
    }

    fun saveSummaryFeedback(id: String, text: String, dismiss:Boolean=true,onSaved:()->Unit={}) = mutate {
        repository.saveFeedback(id, text)
        if(dismiss)dismissSummary()
        onSaved()
    }

    fun addExercise(sessionId: String, exerciseId: String, count: Int = WorkoutRules.DEFAULT_SET_COUNT) = mutate {
        selection.value = addDefault(sessionId, exerciseId, count)
    }

    private suspend fun addDefault(sessionId:String,id:String,count:Int):String {
        val metric=repository.observeExercises().first().find {it.id==id}?.recordMetric ?: RecordMetric.REPETITIONS
        return add(sessionId,id,count,metric.defaultMin,metric.defaultMax)
    }
    fun setDefaultWeight(exerciseId: String, weight: Long?) = mutate { container.training.setDefaultWeight(exerciseId, weight) }

    fun updateDraft(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int) = mutate {
        reopenIfReviewing()
        update(sessionExerciseId, setId, weightGrams, reps)
    }

    fun updateDraftKilograms(sessionExerciseId: String, setId: String, kilograms: String, reps: String) = mutate {
        val parsedReps = reps.trim().toIntOrNull() ?: throw WorkoutException(WorkoutError.INVALID_INPUT, "请输入有效次数")
        update(sessionExerciseId, setId, WeightInput.kilogramsToGrams(kilograms), parsedReps)
    }

    fun adjustDraft(sessionExerciseId: String, setId: String, weightDeltaGrams: Long = 0, repsDelta: Int = 0) = mutate {
        reopenIfReviewing()
        update.adjust(sessionExerciseId, setId, weightDeltaGrams, repsDelta)
    }

    fun setTargetSetCount(sessionExerciseId: String, count: Int) = mutate {
        reopenIfReviewing()
        val sessionId = repository.observeActiveWorkout().first()?.session?.id
        handleSetCount(sessionId, sessionExerciseId, count, setCount(sessionExerciseId, count, finishWhenAllDone = true))
    }

    fun adjustTargetSetCount(sessionExerciseId: String, delta: Int) = mutate {
        reopenIfReviewing()
        val sessionId = repository.observeActiveWorkout().first()?.session?.id
        val result = setCount.adjust(sessionExerciseId, delta, finishWhenAllDone = true)
        handleSetCount(sessionId, sessionExerciseId, (result as? SetCountResult.ConfirmationRequired)?.targetCount ?: 0, result)
    }

    fun confirmSetRemoval() = mutate {
        val request = feedback.value.removal ?: return@mutate
        val sessionId = repository.observeActiveWorkout().first()?.session?.id
        handleSetCount(sessionId, request.sessionExerciseId, request.count, setCount(request.sessionExerciseId, request.count, request.completedSetIds, finishWhenAllDone = true))
    }

    fun completeSet(sessionExerciseId: String, setId: String) = mutate {
        reopenIfReviewing()
        val preferences = container.preferences.preferences.first()
        val exercise = repository.observeActiveWorkout().first()?.exercises?.find { it.id == sessionExerciseId }
            ?: throw WorkoutException(WorkoutError.NOT_FOUND, "训练动作不存在")
        val draft = exercise.sets.find { it.id == setId }
            ?: throw WorkoutException(WorkoutError.NOT_FOUND, "组记录不存在")
        val result = complete(sessionExerciseId, setId, draft.weightGrams, draft.reps,
            if (preferences.restEnabled) exercise.restSecondsSnapshot ?: preferences.restSeconds else 0, finishWhenAllDone = true)
        if (result is CompletionResult.Completed) {
            completions.emit(result.record)
            repository.observeWorkout(exercise.sessionId).first()?.let(::announceFinished)
        }
    }

    fun setPaused(paused: Boolean) = mutate {
        val workout = repository.observeActiveWorkout().first() ?: return@mutate
        if (paused) repository.pauseWorkout(workout.session.id)
        else repository.resumeWorkout(workout.session.id)
    }
    fun skipRest() = mutate { repository.observeActiveWorkout().first()?.let { repository.skipRest(it.session.id) } }

    fun undoSet(sessionExerciseId: String, setId: String) = mutate { reopenIfReviewing(); undo(sessionExerciseId, setId) }

    /** Today's plan finishing keeps the page and asks back / extra; any other session shows the usual summary. */
    private fun announceFinished(saved: Workout) {
        if (saved.session.status != SessionStatus.FINISHED) return
        if (saved.session.dailyPlanDate == java.time.LocalDate.now()) feedback.update { it.copy(planDone = saved, reviewing = saved.session.id, removal = null) }
        else feedback.update { it.copy(summary = saved, removal = null) }
    }

    fun finishWorkout(sessionId: String, feedbackText: String? = null) = mutate {
        val summary = finish(sessionId, feedbackText)
        // A day record stopped before any completed set is dropped, so there is nothing to sum up.
        feedback.update { it.copy(summary = summary.takeIf { s -> s.session.dailyPlanDate == null || s.completedSetCount > 0 }, removal = null) }
    }

    private suspend fun handleSetCount(sessionId: String?, id: String, count: Int, result: SetCountResult) {
        if (result == SetCountResult.Updated && sessionId != null) {
            repository.observeWorkout(sessionId).first()?.let(::announceFinished)
        }
        feedback.update { it.copy(removal = when (result) {
            SetCountResult.Updated -> null
            is SetCountResult.ConfirmationRequired -> SetRemovalRequest(id, count, result.completedSetIds)
        }) }
    }

    private fun mutate(action: suspend () -> Unit) = viewModelScope.launch {
        try {
            container.ready.await()
            writes.withLock {
                action()
                clearError()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            feedback.update { it.copy(error = error.message ?: "保存训练失败") }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(WorkoutViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return WorkoutViewModel(container) as T
        }
    }
}
