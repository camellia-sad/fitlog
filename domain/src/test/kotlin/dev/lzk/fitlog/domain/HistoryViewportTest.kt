package dev.lzk.fitlog.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HistoryViewportTest {
    private val today = LocalDate.parse("2026-11-02")
    @Test fun datesUseCalendarDistanceAndRecentRangeEndsToday() {
        val view = HistoryViewport.forDates(listOf(today.minusDays(300), today.minusDays(150)), today)
        assertEquals(302.0, view.span, .001)
        assertEquals(30.0, view.recent(30).span, .001)
        assertEquals(today.plusDays(1), view.recent(30).toDate)
        assertEquals(view, view.recent(30).all())
    }
    @Test fun pinchKeepsTheDateUnderTheFingerAndPanClamps() {
        val view = HistoryViewport(0.0, 400.0).recent(200)
        val zoomed = view.transform(2.0, 0.0, .25)
        assertEquals(view.start + view.span * .25, zoomed.start + zoomed.span * .25, .001)
        assertEquals(100.0, zoomed.span, .001)
        val older = zoomed.transform(1.0, .2)
        assertTrue(older.start < zoomed.start)
        assertEquals(0.0, older.transform(1.0, 100.0).start, .001)
        assertEquals(400.0, older.transform(1.0, -100.0).end, .001)
        assertEquals(7.0, view.transform(1000.0, 0.0).span, .001)
        assertEquals(400.0, view.transform(.001, 0.0).span, .001)
        assertEquals(view, view.transform(Double.NaN, 0.0))
    }
    @Test fun emptyAndSingleDayHaveAUsableWeek() {
        assertEquals(7.0, HistoryViewport.forDates(emptyList(), today).span, .001)
        val view = HistoryViewport.forDates(listOf(today), today)
        assertEquals(view, view.transform(100.0, 0.0))
    }
    @Test fun dailyPeaksSortDatesKeepHighestAndSeparateUnits() {
        fun point(id: String, days: Long, weight: Long, reps: Int, metric: RecordMetric = RecordMetric.REPETITIONS) =
            PerformancePoint(id, today.plusDays(days), weight, reps, metric)
        val points = listOf(point("b", 0, 20_000, 12), point("a", -90, 40_000, 8),
            point("c", 0, 50_000, 6), point("d", 0, 0, 120, RecordMetric.SECONDS))
        val weight = dailyPerformance(points, RecordMetric.REPETITIONS, false)
        assertEquals(listOf(today.minusDays(90), today), weight.map { it.date })
        assertEquals(listOf(40_000L, 50_000L), weight.map { it.weightGrams })
        assertEquals(12, dailyPerformance(points, RecordMetric.REPETITIONS, true).last().reps)
        assertEquals(120, dailyPerformance(points, RecordMetric.SECONDS, true).single().reps)
    }
}
