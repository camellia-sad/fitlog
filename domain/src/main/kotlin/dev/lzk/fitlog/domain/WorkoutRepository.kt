package dev.lzk.fitlog.domain

import kotlinx.coroutines.flow.Flow

interface WorkoutRepository {
    fun observeExercises(query: String = "", equipment: EquipmentType? = null, muscle: Muscle? = null, archived: Boolean = false): Flow<List<Exercise>>
    fun observeActiveWorkout(): Flow<Workout?>
    fun observeWorkout(sessionId: String): Flow<Workout?>
    fun observeDailyWorkout(date: java.time.LocalDate): Flow<Workout?>
    suspend fun reserveDailyWorkout(templateId: String? = null, exerciseIds: List<String> = emptyList()): Workout
    suspend fun startDailyWorkout(templateId: String? = null, exerciseIds: List<String> = emptyList()): Workout
    suspend fun addDailyExercise(exerciseId: String)
    suspend fun addDailySet(sessionExerciseId: String)
    suspend fun removeExercise(sessionExerciseId: String)
    suspend fun startWorkout(templateId: String? = null, exerciseIds: List<String> = emptyList()): Workout
    suspend fun addExercise(sessionId: String, exerciseId: String, setCount: Int, repMin: Int, repMax: Int): String
    suspend fun updateSetDraft(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int)
    suspend fun adjustSetDraft(sessionExerciseId: String, setId: String, weightDeltaGrams: Long, repsDelta: Int)
    suspend fun adjustSetCount(sessionExerciseId: String, count: Int, confirmedCompletedSetIds: Set<String>, finishWhenAllDone: Boolean = false): SetCountResult
    suspend fun changeSetCount(sessionExerciseId: String, delta: Int, finishWhenAllDone: Boolean = false): SetCountResult
    suspend fun completeSet(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int, restSeconds: Int = 0, finishWhenAllDone: Boolean = false): CompletionResult
    suspend fun undoCompletedSet(sessionExerciseId: String, setId: String)
    suspend fun pauseWorkout(sessionId: String)
    suspend fun resumeWorkout(sessionId: String)
    suspend fun skipRest(sessionId: String)
    suspend fun finishWorkout(sessionId: String, feedback: String?): Workout
    suspend fun saveFeedback(sessionId: String, feedback: String?)
    suspend fun getLastExercisePerformance(exerciseId: String, excludingSessionId: String?): ExercisePerformance?
    /** Heaviest completed set of this exercise in any other kept session: the bar a new record has to clear. */
    suspend fun previousBestWeight(exerciseId: String, excludingSessionId: String): Long?
    /** Reopens today's finished plan for an edit (undo or extra set), resuming the timer without the stopped stretch. */
    suspend fun reopenDailyWorkout(sessionId: String)
}
