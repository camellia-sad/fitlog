package dev.lzk.fitlog.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.Flow

/**
 * A cycle of templates (null = rest) starting on [startDate]. Each date in [postponedDates] is skipped: that day
 * becomes a rest day and every later day of the cycle moves back by one, so nothing in the cycle is lost.
 */
data class TrainingPlan(val startDate: LocalDate, val cycleDays: Int = 7, val repeats: Boolean = true,
    val templateIds: List<String?> = List(cycleDays) { null }, val postponedDates: Set<LocalDate> = emptySet()) {
    fun validate() {
        require(cycleDays in 1..14 && templateIds.size == cycleDays) { "计划周期须为 1～14 天" }
        require(templateIds.all { it == null || it.isNotBlank() }) { "请选择有效模板" }
        require(postponedDates.all { !it.isBefore(startDate) }) { "推迟日期早于计划开始" }
    }
    fun isPostponed(date: LocalDate): Boolean = date in postponedDates
    fun dayIndex(date: LocalDate): Int? {
        if (isPostponed(date)) return null
        val offset = ChronoUnit.DAYS.between(startDate, date) - postponedDates.count { it.isBefore(date) && !it.isBefore(startDate) }
        if (offset < 0 || (!repeats && offset >= cycleDays)) return null
        return (offset % cycleDays).toInt()
    }
    fun templateOn(date: LocalDate): String? = dayIndex(date)?.let { templateIds[it] }
    /** Only a planned training day can be pushed to tomorrow. */
    fun canPostpone(date: LocalDate): Boolean = templateOn(date) != null
    fun postpone(date: LocalDate): TrainingPlan {
        require(canPostpone(date)) { "今天没有可推迟的训练" }
        return copy(postponedDates = postponedDates + date)
    }
    fun undoPostpone(date: LocalDate): TrainingPlan = copy(postponedDates = postponedDates - date)
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
