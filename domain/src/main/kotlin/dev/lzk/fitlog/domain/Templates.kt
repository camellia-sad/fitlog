package dev.lzk.fitlog.domain

import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class TemplateExercise(
    val id: String,
    val exerciseId: String,
    val sortOrder: Int,
    val targetSetCount: Int = 3,
    val targetRepMin: Int = 8,
    val targetRepMax: Int = 12,
    val defaultWeightGrams: Long = 0,
    val restSeconds: Int = 90,
    val useExerciseWeight: Boolean = defaultWeightGrams == 0L,
)

data class WorkoutTemplate(
    val id: String, val name: String, val splitType: SplitType,
    val createdAt: Instant, val updatedAt: Instant, val exercises: List<TemplateExercise>,
)

data class TemplateDraft(
    val id: String? = null, val name: String = "", val splitType: SplitType = SplitType.FREE,
    val exercises: List<TemplateExercise> = emptyList(),
) {
    fun validate() {
        WorkoutRules.input(name.trim().length in 1..80, "模板名称须为 1～80 字")
        WorkoutRules.input(exercises.size in 1..30, "模板须包含 1～30 个动作")
        WorkoutRules.input(exercises.map { it.id }.distinct().size == exercises.size, "模板动作 ID 重复")
        exercises.forEach {
            WorkoutRules.setCount(it.targetSetCount)
            WorkoutRules.repRange(it.targetRepMin, it.targetRepMax)
            WorkoutRules.draft(it.defaultWeightGrams, 0)
            WorkoutRules.rest(it.restSeconds)
        }
    }
}

interface TemplateDraftRepository {
    fun observeTemplateDrafts(): Flow<List<TemplateDraft>>
    suspend fun saveTemplateDraft(draft: TemplateDraft)
    suspend fun deleteTemplateDraft(templateId: String?)
}

interface TemplateRepository {
    fun observeTemplates(): Flow<List<WorkoutTemplate>>
    suspend fun saveTemplate(draft: TemplateDraft): String
    suspend fun deleteTemplate(id: String)
}

class SaveTemplateUseCase(private val repository: TemplateRepository) {
    suspend operator fun invoke(draft: TemplateDraft): String { draft.validate(); return repository.saveTemplate(draft) }
}
