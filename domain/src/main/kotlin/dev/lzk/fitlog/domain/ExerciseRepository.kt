package dev.lzk.fitlog.domain

data class CustomExerciseDraft(
    val name: String, val nameEn: String = "", val equipment: EquipmentType,
    val primaryMuscles: Set<Muscle>, val instructions: String = "",
    val secondaryMuscles: Set<Muscle> = emptySet(), val recordMetric: RecordMetric = RecordMetric.REPETITIONS,
) {
    fun validate() {
        WorkoutRules.input(name.trim().length in 1..80, "动作名称须为 1～80 字")
        WorkoutRules.input(primaryMuscles.isNotEmpty(), "至少选择一个主练部位")
        WorkoutRules.input(primaryMuscles.none { it in secondaryMuscles }, "同一部位不能既是主练又是辅助")
        WorkoutRules.input(instructions.length <= 2000, "动作说明最多 2000 字")
    }
}

interface ExerciseRepository {
    suspend fun setDefaultWeight(id: String, weightGrams: Long?)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun createCustomExercise(draft: CustomExerciseDraft): String
    suspend fun setArchived(id: String, archived: Boolean)
}

data class ImportResult(val sessionsAdded: Int, val templatesAdded: Int, val exercisesAdded: Int)
interface BackupRepository {
    suspend fun exportJson(): String
    suspend fun importJson(json: String): ImportResult
}
