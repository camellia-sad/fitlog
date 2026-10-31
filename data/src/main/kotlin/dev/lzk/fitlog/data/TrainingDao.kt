package dev.lzk.fitlog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingDao {
    @Query("SELECT * FROM template_drafts ORDER BY templateId IS NOT NULL, id")
    fun observeTemplateDrafts(): Flow<List<TemplateDraftEntity>>
    @Query("SELECT * FROM template_drafts ORDER BY id") suspend fun allDrafts(): List<TemplateDraftEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTemplateDraft(draft: TemplateDraftEntity)
    @Query("DELETE FROM template_drafts WHERE id = :id") suspend fun deleteTemplateDraft(id: String)

    @Transaction @Query("SELECT * FROM training_plans WHERE id = 'current'")
    fun observePlan(): Flow<PlanRow?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlan(plan: TrainingPlanEntity)
    @Insert suspend fun insertPlanDays(days: List<PlanDayEntity>)
    @Query("DELETE FROM training_plans") suspend fun deletePlan()
    @Query("SELECT * FROM training_plans") suspend fun allPlans(): List<TrainingPlanEntity>
    @Query("SELECT * FROM plan_days ORDER BY planId, dayIndex") suspend fun allPlanDays(): List<PlanDayEntity>
    @Insert suspend fun importPlans(plans: List<TrainingPlanEntity>)

    @Transaction @Query("SELECT * FROM workout_templates ORDER BY updatedAt DESC, id")
    fun observeTemplates(): Flow<List<TemplateRow>>
    @Transaction @Query("SELECT * FROM workout_templates WHERE id = :id")
    suspend fun template(id: String): TemplateRow?
    @Insert suspend fun insertTemplate(template: TemplateEntity)
    @Update suspend fun updateTemplate(template: TemplateEntity)
    @Insert suspend fun insertTemplateExercises(exercises: List<TemplateExerciseEntity>)
    @Query("DELETE FROM template_exercises WHERE templateId = :id") suspend fun deleteTemplateExercises(id: String)
    @Query("DELETE FROM workout_templates WHERE id = :id") suspend fun deleteTemplate(id: String)

    @Query("UPDATE exercises SET isFavorite = :favorite WHERE id = :id") suspend fun favorite(id: String, favorite: Boolean): Int
    @Query("UPDATE exercises SET isArchived = :archived WHERE id = :id") suspend fun archive(id: String, archived: Boolean): Int
    @Update suspend fun updateExercise(exercise: ExerciseEntity)
    @Query("DELETE FROM exercise_muscles WHERE exerciseId = :id") suspend fun deleteMuscles(id: String)

    @Transaction @Query(WORKOUT_SELECT + "WHERE localWorkoutDate BETWEEN :from AND :to ORDER BY startedAtUtc DESC, id DESC")
    fun observeHistory(from: String, to: String): Flow<List<WorkoutRow>>
    @Transaction @Query(WORKOUT_SELECT + """
        WHERE status = 'FINISHED'
        AND EXISTS (SELECT 1 FROM session_exercises e WHERE e.sessionId = s.id AND e.exerciseId = :exerciseId)
        ORDER BY startedAtUtc DESC, id DESC
    """)
    fun observeExerciseHistory(exerciseId: String): Flow<List<WorkoutRow>>

    @Transaction @Query(WORKOUT_SELECT + """WHERE status = 'FINISHED' AND
        (startedAtUtc < :before OR (startedAtUtc = :before AND id < :beforeId))
        ORDER BY startedAtUtc DESC, id DESC LIMIT 50""")
    suspend fun sharingPage(before: Long, beforeId: String): List<WorkoutRow>

    @Query("SELECT * FROM exercises ORDER BY id") suspend fun allExercises(): List<ExerciseEntity>
    @Query("SELECT * FROM exercise_muscles ORDER BY exerciseId, muscle, role") suspend fun allMuscles(): List<ExerciseMuscleEntity>
    @Query("SELECT * FROM workout_templates ORDER BY id") suspend fun allTemplates(): List<TemplateEntity>
    @Query("SELECT * FROM template_exercises ORDER BY templateId, sortOrder") suspend fun allTemplateExercises(): List<TemplateExerciseEntity>
    @Query("SELECT * FROM workout_sessions ORDER BY startedAtUtc, id") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT * FROM session_exercises ORDER BY sessionId, sortOrder") suspend fun allSessionExercises(): List<SessionExerciseEntity>
    @Query("SELECT * FROM set_records ORDER BY sessionExerciseId, setIndex") suspend fun allSets(): List<SetEntity>

    @Insert suspend fun importExercises(exercises: List<ExerciseEntity>)
    @Insert suspend fun importMuscles(muscles: List<ExerciseMuscleEntity>)
    @Insert suspend fun importTemplates(templates: List<TemplateEntity>)
    @Insert suspend fun importSessions(sessions: List<SessionEntity>)
    @Insert suspend fun importSessionExercises(exercises: List<SessionExerciseEntity>)
}
