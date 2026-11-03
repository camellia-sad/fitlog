package dev.lzk.fitlog.domain

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

/** A calendar-day viewport; changing scale preserves the date beneath the fingers. */
data class HistoryViewport(val lower: Double, val upper: Double, val start: Double = lower, val end: Double = upper) {
    val span: Double get() = end - start
    private val minimumSpan: Double get() = minOf(7.0, upper - lower)
    fun transform(zoom: Double, panFraction: Double, anchorFraction: Double = .5): HistoryViewport {
        if(!zoom.isFinite() || zoom <= 0 || !panFraction.isFinite() || !anchorFraction.isFinite()) return this
        val nextSpan = (span / zoom).coerceIn(minimumSpan, upper - lower)
        val anchor = anchorFraction.coerceIn(0.0, 1.0)
        val nextStart = (start + span * anchor - nextSpan * anchor - panFraction * span)
            .coerceIn(lower, upper - nextSpan)
        return copy(start = nextStart, end = nextStart + nextSpan)
    }
    fun recent(days: Int): HistoryViewport {
        val width = days.toDouble().coerceIn(minimumSpan, upper - lower)
        return copy(start = upper - width, end = upper)
    }
    fun all(): HistoryViewport = copy(start = lower, end = upper)
    val fromDate: LocalDate get() = LocalDate.ofEpochDay(floor(start).toLong())
    val toDate: LocalDate get() = LocalDate.ofEpochDay(ceil(end).toLong())
    companion object {
        fun forDates(dates: List<LocalDate>, today: LocalDate): HistoryViewport {
            val first = dates.minOrNull() ?: today
            val last = maxOf(dates.maxOrNull() ?: today, today)
            val end = last.toEpochDay().toDouble() + 1
            return HistoryViewport(minOf(first.toEpochDay().toDouble() - 1, end - 7), end)
        }
    }
}

fun dailyPerformance(points: List<PerformancePoint>, metric: RecordMetric, repetitions: Boolean): List<PerformancePoint> =
    points.filter { it.recordMetric == metric }.groupBy { it.date }.toSortedMap().values.map { day ->
        if(repetitions || metric == RecordMetric.SECONDS) day.maxWith(compareBy<PerformancePoint> { it.reps }.thenBy { it.weightGrams })
        else day.maxWith(compareBy<PerformancePoint> { it.weightGrams }.thenBy { it.reps })
    }
