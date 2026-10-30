package dev.lzk.fitlog.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TrainingPlanTest {
    @Test fun cyclesAndNonRepeatingBoundariesAreBasedOnLocalDates() {
        val start = LocalDate.of(2026, 10, 26)
        val weekly = TrainingPlan(start, templateIds = listOf("push", null, "pull", null, null, "legs", null))
        assertEquals("push", weekly.templateOn(start.plusDays(7)))
        assertNull(weekly.dayIndex(start.minusDays(1)))
        val fortnight = TrainingPlan(start, 14, true, List(14) { if (it == 13) "legs" else null })
        assertEquals("legs", fortnight.templateOn(start.plusDays(27)))
        assertEquals("legs", fortnight.copy(repeats = false).templateOn(start.plusDays(13)))
        assertNull(fortnight.copy(repeats = false).dayIndex(start.plusDays(14)))
    }
    @Test fun rejectsCyclesBeyondTwoWeeksOrIncompleteDayMaps() {
        assertThrows(IllegalArgumentException::class.java) { TrainingPlan(LocalDate.now(), 15).validate() }
        assertThrows(IllegalArgumentException::class.java) { TrainingPlan(LocalDate.now(), 7, templateIds = listOf(null)).validate() }
    }
}
