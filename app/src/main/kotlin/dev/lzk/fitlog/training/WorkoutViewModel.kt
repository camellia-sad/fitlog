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
    val selectedExercise: SessionExercise? = null,
    val lastPerformance: ExercisePerformance? = null,
    val summary: Workout? = null,
    val removalRequest: SetRemovalRequest? = null,
    val error: String? = null,
)

private data class LibraryFilter(val query: String = "", val equipment: EquipmentType? = null, val muscle: Muscle? = null, val favorites: Boolean = false, val archived: Boolean = false)
private data class Feedback(val summary: Workout? = null, val removal: SetRemovalRequest? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(private val container: AppContainer) : ViewModel() {
    private val repository = container.workouts
    private val writes = Mutex()
    private val getExercises = GetExercisesUseCase(repository)
    private val start = StartWorkoutUseCase(repository)
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
                selectedExercise = workout?.exercises?.find { it.id == selected } ?: workout?.exercises?.firstOrNull(),
                summary = feedback.summary, removalRequest = feedback.removal, error = feedback.error,
            )
        }.combine(container.preparation.preparedExercises) { state, ids -> state.copy(preparedExerciseIds = ids) }.mapLatest { state ->
            val exercise = state.selectedExercise
            state.copy(lastPerformance = exercise?.let { last(it.exerciseId, it.sessionId) })
        })
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(WorkoutUiState(loading = false, error = error.message ?: "读取训练失败"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUiState())

    val currentSearchQuery: String get() = filters.value.query

    fun search(text: String) { filters.update { it.copy(query = text) } }
    fun equipment(value: EquipmentType?) { filters.update { it.copy(equipment = value) } }
    fun muscle(value: Muscle?) { filters.update { it.copy(muscle = value) } }
    fun favorites(value: Boolean) { filters.update { it.copy(favorites = value) } }
    fun archived(value: Boolean) { filters.update { it.copy(archived = value) } }
    fun favorite(exercise: Exercise) = mutate { container.training.setFavorite(exercise.id, !exercise.isFavorite) }
    fun archive(exercise: Exercise) = mutate { container.training.setArchived(exercise.id, !exercise.isArchived) }
    fun createCustom(draft: CustomExerciseDraft) = mutate { container.training.createCustomExercise(draft) }
    fun selectExercise(id: String) { selection.value = id }
    fun clearError() { feedback.update { it.copy(error = null) } }
    fun dismissSummary() { feedback.update { it.copy(summary = null) } }
    fun cancelSetRemoval() { feedback.update { it.copy(removal = null) } }

    fun startWorkout(templateId: String? = null) = mutate {
        val prepared = if (templateId == null) container.preparation.preparedExercises.first() else emptyList()
        start(templateId, prepared)
        if (templateId == null) container.preparation.savePreparedExercises(emptyList())
        selection.value = null
        feedback.value = Feedback()
    }

    fun startAndAddExercise(exerciseId: String) = mutate {
        val workout = repository.observeActiveWorkout().first()
        if (workout != null) selection.value = addDefault(workout.session.id, exerciseId,WorkoutRules.DEFAULT_SET_COUNT)
        else container.preparation.savePreparedExercises((container.preparation.preparedExercises.first() + exerciseId).distinct())
    }

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
    fun updateDraft(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int) = mutate {
        update(sessionExerciseId, setId, weightGrams, reps)
    }

    fun updateDraftKilograms(sessionExerciseId: String, setId: String, kilograms: String, reps: String) = mutate {
        val parsedReps = reps.trim().toIntOrNull() ?: throw WorkoutException(WorkoutError.INVALID_INPUT, "请输入有效次数")
        update(sessionExerciseId, setId, WeightInput.kilogramsToGrams(kilograms), parsedReps)
    }

    fun adjustDraft(sessionExerciseId: String, setId: String, weightDeltaGrams: Long = 0, repsDelta: Int = 0) = mutate {
        update.adjust(sessionExerciseId, setId, weightDeltaGrams, repsDelta)
    }

    fun setTargetSetCount(sessionExerciseId: String, count: Int) = mutate {
        val sessionId = repository.observeActiveWorkout().first()?.session?.id
        handleSetCount(sessionId, sessionExerciseId, count, setCount(sessionExerciseId, count, finishWhenAllDone = true))
    }

    fun adjustTargetSetCount(sessionExerciseId: String, delta: Int) = mutate {
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
        val preferences = container.preferences.preferences.first()
        val exercise = repository.observeActiveWorkout().first()?.exercises?.find { it.id == sessionExerciseId }
            ?: throw WorkoutException(WorkoutError.NOT_FOUND, "训练动作不存在")
        val draft = exercise.sets.find { it.id == setId }
            ?: throw WorkoutException(WorkoutError.NOT_FOUND, "组记录不存在")
        val result = complete(sessionExerciseId, setId, draft.weightGrams, draft.reps,
            if (preferences.restEnabled) exercise.restSecondsSnapshot ?: preferences.restSeconds else 0, finishWhenAllDone = true)
        if (result is CompletionResult.Completed) {
            completions.emit(result.record)
            repository.observeWorkout(exercise.sessionId).first()?.let { saved ->
                if (saved.session.status == SessionStatus.FINISHED) feedback.update { it.copy(summary = saved, removal = null) }
            }
        }
    }

    fun setPaused(paused: Boolean) = mutate {
        val workout = repository.observeActiveWorkout().first() ?: return@mutate
        if (paused) repository.pauseWorkout(workout.session.id)
        else repository.resumeWorkout(workout.session.id)
    }
    fun skipRest() = mutate { repository.observeActiveWorkout().first()?.let { repository.skipRest(it.session.id) } }

    fun undoSet(sessionExerciseId: String, setId: String) = mutate { undo(sessionExerciseId, setId) }

    fun finishWorkout(sessionId: String, feedbackText: String? = null) = mutate {
        val summary = finish(sessionId, feedbackText)
        feedback.update { it.copy(summary = summary, removal = null) }
    }

    private suspend fun handleSetCount(sessionId: String?, id: String, count: Int, result: SetCountResult) {
        if (result == SetCountResult.Updated && sessionId != null) {
            repository.observeWorkout(sessionId).first()?.let { saved ->
                if (saved.session.status == SessionStatus.FINISHED) feedback.update { it.copy(summary = saved) }
            }
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
