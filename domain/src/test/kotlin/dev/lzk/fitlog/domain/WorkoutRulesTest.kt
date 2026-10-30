package dev.lzk.fitlog.domain

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WorkoutRulesTest {
    @Test fun decimalWeightsUseExactGrams() {
        assertEquals(37_500L, WeightInput.kilogramsToGrams("37.5"))
        assertEquals(42_500L, WeightInput.kilogramsToGrams("42.500"))
        assertEquals("42.5", WeightInput.gramsToKilograms(42_500))
        assertEquals(0L, WeightInput.kilogramsToGrams("0"))
        assertEquals(1L, WeightInput.kilogramsToGrams("0.001"))
    }

    @Test fun invalidOrOverflowingWeightsAreRejected() {
        for (text in listOf("-1", "NaN", "Infinity", "", "1e3", "0.0001", "9223372036854776", "1,5")) {
            val error = assertThrows(WorkoutException::class.java) { WeightInput.kilogramsToGrams(text) }
            assertEquals(WorkoutError.INVALID_INPUT, error.error)
        }
    }

    @Test fun zeroWeightIsValidButZeroCompletedRepsAreNot() {
        WorkoutRules.completion(0, 10)
        WorkoutRules.draft(0, 0)
        assertThrows(WorkoutException::class.java) { WorkoutRules.completion(0, 0) }
        assertThrows(WorkoutException::class.java) { WorkoutRules.draft(-1, 10) }
        assertThrows(WorkoutException::class.java) { WorkoutRules.draft(0, -1) }
        assertThrows(WorkoutException::class.java) { WorkoutRules.setCount(0) }
        assertThrows(WorkoutException::class.java) { WorkoutRules.setCount(Int.MAX_VALUE) }
    }

    @Test fun summaryCountsOnlyActualCompletedGroups() {
        val now = Instant.parse("2026-10-28T01:00:00Z")
        val session = WorkoutSession("s", now, LocalDate.parse("2026-10-28"), SessionStatus.ACTIVE)
        val exercise = SessionExercise("se", "s", "e", 0, "俯卧撑", 3, 8, 12, listOf(
            SetRecord("1", "se", 1, 0, 10, SetStatus.COMPLETED, now),
            SetRecord("2", "se", 2, 50_000, 12, SetStatus.PLANNED),
            SetRecord("3", "se", 3, 0, 0, SetStatus.SKIPPED),
        ))
        val workout = Workout(session, listOf(exercise))
        assertEquals(1, workout.completedSetCount)
        assertEquals(1, workout.completedExerciseCount)
        assertEquals(3, workout.plannedSetCount)
        assertEquals(0L, workout.elapsedDuration(now.minusSeconds(30)).seconds)
        assertEquals(90L, workout.elapsedDuration(now.plusSeconds(90)).seconds)
    }
}
