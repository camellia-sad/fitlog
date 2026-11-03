package dev.lzk.fitlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val WORKOUT_SELECT = """SELECT s.*, EXISTS(SELECT 1 FROM workout_sessions p
    WHERE p.localWorkoutDate = s.localWorkoutDate AND (p.startedAtUtc < s.startedAtUtc
    OR (p.startedAtUtc = s.startedAtUtc AND p.id < s.id))) AS isExtra FROM workout_sessions s """

@Dao
interface WorkoutDao {
    @Transaction
    @Query("""
        SELECT e.*, (SELECT MAX(sr.weightGrams) FROM set_records sr
            JOIN session_exercises se ON se.id = sr.sessionExerciseId
            JOIN workout_sessions ws ON ws.id = se.sessionId
            WHERE se.exerciseId = e.id AND se.recordMetricSnapshot = 'REPETITIONS'
            AND sr.status = 'COMPLETED' AND ws.status != 'DISCARDED') AS bestWeightGrams
        FROM exercises e WHERE isArchived = :archived
        AND (instr(lower(nameZh), lower(:query)) > 0 OR instr(lower(nameEn), lower(:query)) > 0)
        AND (:equipment IS NULL OR equipmentType = :equipment)
        AND (:muscle IS NULL OR EXISTS (SELECT 1 FROM exercise_muscles m WHERE m.exerciseId = e.id AND m.muscle = :muscle))
        ORDER BY nameZh, id
    """)
    fun observeExercises(query: String, equipment: String?, muscle: String?, archived: Boolean): Flow<List<ExerciseRow>>

    @Query("""SELECT MAX(sr.weightGrams) FROM set_records sr
        JOIN session_exercises se ON se.id = sr.sessionExerciseId
        JOIN workout_sessions ws ON ws.id = se.sessionId
        WHERE se.exerciseId = :id AND se.recordMetricSnapshot = 'REPETITIONS'
        AND sr.status = 'COMPLETED' AND ws.status != 'DISCARDED'""")
    suspend fun bestWeight(id: String): Long?

    @Query("UPDATE exercises SET defaultWeightGrams = :weight, defaultWeightRecordGrams = :record WHERE id = :id")
    suspend fun defaultWeight(id: String, weight: Long?, record: Long): Int

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun exercise(id: String): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExercises(exercises: List<ExerciseEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMuscles(muscles: List<ExerciseMuscleEntity>)

    @Query("SELECT version FROM catalog_versions WHERE id = 'builtin'")
    suspend fun catalogVersion(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCatalogVersion(version: CatalogVersionEntity)

    @Transaction
    @Query(WORKOUT_SELECT + "WHERE unfinishedSlot = 1 LIMIT 1")
    fun observeActiveWorkout(): Flow<WorkoutRow?>

    @Transaction
    @Query(WORKOUT_SELECT + "WHERE id = :id")
    fun observeWorkout(id: String): Flow<WorkoutRow?>

    @Transaction
    @Query(WORKOUT_SELECT + "WHERE id = :id")
    suspend fun workout(id: String): WorkoutRow?

    @Query("SELECT * FROM workout_sessions WHERE unfinishedSlot = 1 LIMIT 1")
    suspend fun activeSession(): SessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("DELETE FROM workout_sessions")
    suspend fun clearSessions()

    @Query("SELECT MAX(startedAtUtc) FROM workout_sessions WHERE localWorkoutDate = :date")
    suspend fun lastStart(date: String): Long?

    @Insert suspend fun insertSession(session: SessionEntity)
    @Update suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM session_exercises WHERE id = :id")
    suspend fun sessionExercise(id: String): SessionExerciseEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM session_exercises WHERE sessionId = :sessionId")
    suspend fun nextExerciseOrder(sessionId: String): Int

    @Insert suspend fun insertSessionExercise(exercise: SessionExerciseEntity)
    @Update suspend fun updateSessionExercise(exercise: SessionExerciseEntity)

    @Query("SELECT * FROM set_records WHERE id = :id")
    suspend fun set(id: String): SetEntity?

    @Query("SELECT * FROM set_records WHERE sessionExerciseId = :exerciseId ORDER BY setIndex")
    suspend fun sets(exerciseId: String): List<SetEntity>

    @Insert suspend fun insertSets(sets: List<SetEntity>)
    @Update suspend fun updateSet(set: SetEntity)

    @Query("UPDATE set_records SET weightGrams = :weight, reps = :reps, status = 'COMPLETED', completedAtUtc = :at WHERE id = :id AND status = 'PLANNED'")
    suspend fun completePlannedSet(id: String, weight: Long, reps: Int, at: Long): Int

    @Query("DELETE FROM set_records WHERE sessionExerciseId = :id AND setIndex > :count")
    suspend fun deleteTrailingSets(id: String, count: Int)

    @Transaction
    @Query("""
        SELECT se.* FROM session_exercises se
        JOIN workout_sessions ws ON ws.id = se.sessionId
        WHERE se.exerciseId = :exerciseId AND ws.status != 'DISCARDED'
        AND (:excludingSessionId IS NULL OR ws.id != :excludingSessionId)
        AND EXISTS (SELECT 1 FROM set_records sr WHERE sr.sessionExerciseId = se.id AND sr.status = 'COMPLETED')
        ORDER BY (SELECT MAX(sr.completedAtUtc) FROM set_records sr WHERE sr.sessionExerciseId = se.id AND sr.status = 'COMPLETED') DESC,
        ws.startedAtUtc DESC, se.sortOrder DESC, se.id DESC LIMIT 1
    """)
    suspend fun lastPerformance(exerciseId: String, excludingSessionId: String?): SessionExerciseRow?
}
