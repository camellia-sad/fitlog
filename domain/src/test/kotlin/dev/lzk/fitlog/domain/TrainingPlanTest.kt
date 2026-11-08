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
    @Test fun postponingTodayRestsItAndShiftsTheRestOfTheCycleByOneDay() {
        val start = LocalDate.of(2026, 11, 2)
        val plan = TrainingPlan(start, templateIds = listOf("push", "pull", "legs", null, "push", "full", null))
        val today = start.plusDays(5)
        assertEquals("full", plan.templateOn(today))
        val later = plan.postpone(today)
        assertTrue(later.isPostponed(today))
        assertNull(later.templateOn(today))
        assertEquals("push", later.templateOn(start.plusDays(4)))
        assertEquals("full", later.templateOn(today.plusDays(1)))
        assertNull(later.templateOn(today.plusDays(2)))
        assertEquals("push", later.templateOn(today.plusDays(3)))
        // A second day off pushes everything again; undoing restores the original schedule.
        val twice = later.postpone(today.plusDays(1))
        assertEquals("full", twice.templateOn(today.plusDays(2)))
        assertEquals(plan.templateOn(today.plusDays(9)), later.undoPostpone(today).templateOn(today.plusDays(9)))
        assertThrows(IllegalArgumentException::class.java) { plan.postpone(start.plusDays(3)) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(postponedDates = setOf(start.minusDays(1))).validate() }
        val once = TrainingPlan(start, 3, false, listOf("a", "b", "c")).postpone(start)
        assertEquals("c", once.templateOn(start.plusDays(3)))
        assertNull(once.templateOn(start.plusDays(4)))
    }
    @Test fun rejectsCyclesBeyondTwoWeeksOrIncompleteDayMaps() {
        assertThrows(IllegalArgumentException::class.java) { TrainingPlan(LocalDate.now(), 15).validate() }
        assertThrows(IllegalArgumentException::class.java) { TrainingPlan(LocalDate.now(), 7, templateIds = listOf(null)).validate() }
    }
}
