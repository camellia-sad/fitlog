package dev.lzk.fitlog.data

import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomTrainingRepository(private val database: FitLogDatabase, private val clock: Clock = Clock.systemUTC()) :
    TemplateRepository, TemplateDraftRepository, HistoryRepository, ExerciseRepository, TrainingPlanRepository {
    private val dao = database.trainingDao()
    private val workouts = database.workoutDao()

    override fun observeTemplateDrafts() = dao.observeTemplateDrafts().map { rows -> rows.map { it.toDraft() } }

    override suspend fun saveTemplateDraft(draft: TemplateDraft) = database.withTransaction {
        draft.id?.let { require(dao.template(it) != null) { "原模板已删除" } }
        dao.putTemplateDraft(draft.toDraftEntity())
    }

    override suspend fun deleteTemplateDraft(templateId: String?) {
        dao.deleteTemplateDraft(templateDraftKey(templateId))
    }

    override fun observePlan() = dao.observePlan().map { row ->
        row?.let { TrainingPlan(LocalDate.parse(it.plan.startDate), it.plan.cycleDays, it.plan.repeats,
            it.days.sortedBy { day -> day.dayIndex }.map { day -> day.templateId }) }
    }
    override suspend fun savePlan(plan: TrainingPlan) = database.withTransaction {
        plan.validate()
        plan.templateIds.filterNotNull().forEach { require(dao.template(it) != null) { "计划中的模板已不存在" } }
        dao.deletePlan()
        dao.putPlan(TrainingPlanEntity(startDate = plan.startDate.toString(), cycleDays = plan.cycleDays, repeats = plan.repeats))
        dao.insertPlanDays(plan.templateIds.mapIndexed { index, id -> PlanDayEntity(dayIndex = index, templateId = id) })
    }
    override suspend fun deletePlan() = database.withTransaction { dao.deletePlan() }

    override fun observeTemplates() = dao.observeTemplates().map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveTemplate(draft: TemplateDraft): String = database.withTransaction {
        draft.validate()
        draft.exercises.forEach {
            val exercise = workouts.exercise(it.exerciseId) ?: error("动作不存在")
            WorkoutRules.input(!exercise.isArchived, "模板包含已归档动作")
            WorkoutRules.input(exercise.recordMetric!="SECONDS" || it.defaultWeightGrams==0L,"时长动作不记录重量")
        }
        val existing = draft.id?.let { dao.template(it) }
        if (draft.id != null && existing == null) throw WorkoutException(WorkoutError.NOT_FOUND, "模板不存在")
        val id = draft.id ?: UUID.randomUUID().toString()
        val now = maxOf(clock.millis(), existing?.template?.updatedAt ?: 0)
        val entity = TemplateEntity(id, draft.name.trim(), draft.splitType.name, existing?.template?.createdAt ?: now, now)
        if (existing == null) dao.insertTemplate(entity) else dao.updateTemplate(entity)
        dao.deleteTemplateExercises(id)
        dao.insertTemplateExercises(draft.exercises.mapIndexed { index, item ->
            TemplateExerciseEntity(if (existing?.exercises?.any { it.id == item.id } == true) item.id else UUID.randomUUID().toString(), id, item.exerciseId, index, item.targetSetCount,
                item.targetRepMin, item.targetRepMax, item.defaultWeightGrams, item.restSeconds)
        })
        dao.deleteTemplateDraft(templateDraftKey(draft.id))
        id
    }

    override suspend fun deleteTemplate(id: String) = database.withTransaction { dao.deleteTemplate(id) }

    suspend fun sharingPage(before: Long = Long.MAX_VALUE, beforeId: String = "~"): List<Workout> =
        dao.sharingPage(before, beforeId).map { it.toDomain() }

    override fun observeHistory(from: LocalDate, to: LocalDate) =
        dao.observeHistory(from.toString(), to.toString()).map { rows -> rows.map { it.toDomain() } }

    override fun observeExerciseHistory(exerciseId: String) =
        dao.observeExerciseHistory(exerciseId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        if (dao.favorite(id, favorite) == 0) throw WorkoutException(WorkoutError.NOT_FOUND, "动作不存在")
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        if (dao.archive(id, archived) == 0) throw WorkoutException(WorkoutError.NOT_FOUND, "动作不存在")
    }

    override suspend fun createCustomExercise(draft: CustomExerciseDraft): String = database.withTransaction {
        draft.validate()
        val id = UUID.randomUUID().toString()
        dao.importExercises(listOf(ExerciseEntity(id, draft.name.trim(), draft.nameEn.trim(), draft.equipment.name,
            draft.instructions.trim().ifBlank { null }, null, null, false, true, false)))
        dao.importMuscles(draft.primaryMuscles.map { ExerciseMuscleEntity(id, it.name, "PRIMARY") })
        id
    }
}

internal fun TemplateRow.toDomain() = WorkoutTemplate(
    template.id, template.name, SplitType.valueOf(template.splitType),
    Instant.ofEpochMilli(template.createdAt), Instant.ofEpochMilli(template.updatedAt),
    exercises.sortedBy { it.sortOrder }.map {
        TemplateExercise(it.id, it.exerciseId, it.sortOrder, it.targetSetCount, it.targetRepMin, it.targetRepMax, it.defaultWeightGrams, it.restSeconds)
    },
)
