package dev.lzk.fitlog.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow

data class TrainingPlan(val startDate: LocalDate, val cycleDays: Int = 7, val repeats: Boolean = true,
    val templateIds: List<String?> = List(cycleDays) { null }) {
    fun validate() {
        require(cycleDays in 1..14 && templateIds.size == cycleDays) { "计划周期须为 1～14 天" }
        require(templateIds.all { it == null || it.isNotBlank() }) { "请选择有效模板" }
    }
    fun dayIndex(date: LocalDate): Int? {
        val offset = ChronoUnit.DAYS.between(startDate, date)
        if (offset < 0 || (!repeats && offset >= cycleDays)) return null
        return (offset % cycleDays).toInt()
    }
    fun templateOn(date: LocalDate): String? = dayIndex(date)?.let { templateIds[it] }
}

interface TrainingPlanRepository {
    fun observePlan(): Flow<TrainingPlan?>
    suspend fun savePlan(plan: TrainingPlan)
    suspend fun deletePlan()
}

interface PreparationRepository {
    val preparedExercises: Flow<List<String>>
    suspend fun savePreparedExercises(ids: List<String>)
}
