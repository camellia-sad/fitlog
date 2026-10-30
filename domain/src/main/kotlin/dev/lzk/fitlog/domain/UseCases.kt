package dev.lzk.fitlog.domain

class GetExercisesUseCase(private val repository: WorkoutRepository) {
    operator fun invoke(query: String = "", equipment: EquipmentType? = null, muscle: Muscle? = null, archived: Boolean = false) =
        repository.observeExercises(query.trim(), equipment, muscle, archived)
}

class GetActiveWorkoutUseCase(private val repository: WorkoutRepository) {
    operator fun invoke() = repository.observeActiveWorkout()
}

class StartWorkoutUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(templateId: String? = null, exerciseIds: List<String> = emptyList()) = repository.startWorkout(templateId, exerciseIds)
}

class AddExerciseToSessionUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(
        sessionId: String,
        exerciseId: String,
        setCount: Int = WorkoutRules.DEFAULT_SET_COUNT,
        repMin: Int = WorkoutRules.DEFAULT_REP_MIN,
        repMax: Int = WorkoutRules.DEFAULT_REP_MAX,
    ): String {
        WorkoutRules.setCount(setCount)
        WorkoutRules.repRange(repMin, repMax)
        return repository.addExercise(sessionId, exerciseId, setCount, repMin, repMax)
    }
}

class UpdateSetDraftUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int) {
        WorkoutRules.draft(weightGrams, reps)
        repository.updateSetDraft(sessionExerciseId, setId, weightGrams, reps)
    }

    suspend fun adjust(sessionExerciseId: String, setId: String, weightDeltaGrams: Long = 0, repsDelta: Int = 0) =
        repository.adjustSetDraft(sessionExerciseId, setId, weightDeltaGrams, repsDelta)
}

class AdjustSetCountUseCase(private val repository: WorkoutRepository) {
    suspend fun adjust(sessionExerciseId: String, delta: Int, finishWhenAllDone: Boolean = false): SetCountResult = repository.changeSetCount(sessionExerciseId, delta, finishWhenAllDone)

    suspend operator fun invoke(sessionExerciseId: String, count: Int, confirmedCompletedSetIds: Set<String> = emptySet(), finishWhenAllDone: Boolean = false): SetCountResult {
        WorkoutRules.setCount(count)
        return repository.adjustSetCount(sessionExerciseId, count, confirmedCompletedSetIds, finishWhenAllDone)
    }
}

class CompleteSetUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int, restSeconds: Int = 0, finishWhenAllDone: Boolean = false): CompletionResult {
        WorkoutRules.completion(weightGrams, reps)
        WorkoutRules.rest(restSeconds)
        return repository.completeSet(sessionExerciseId, setId, weightGrams, reps, restSeconds, finishWhenAllDone)
    }
}

class UndoCompletedSetUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(sessionExerciseId: String, setId: String) = repository.undoCompletedSet(sessionExerciseId, setId)
}

class FinishWorkoutUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(sessionId: String, feedback: String? = null) =
        repository.finishWorkout(sessionId, feedback?.trim()?.ifBlank { null })
}

class GetLastExercisePerformanceUseCase(private val repository: WorkoutRepository) {
    suspend operator fun invoke(exerciseId: String, excludingSessionId: String? = null) =
        repository.getLastExercisePerformance(exerciseId, excludingSessionId)
}
