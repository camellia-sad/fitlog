package dev.lzk.fitlog.domain

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TrainingFeaturesTest {
    @Test fun poundsRoundTripAtGramPrecisionWithoutChangingStoredValue() {
        for (grams in listOf(0L, 1L, 37_500L, 42_500L, 150_000L)) {
            val text = WeightUnits.format(grams, WeightUnit.LB, editing = true)
            assertEquals(grams, WeightUnits.parse(text, WeightUnit.LB))
        }
        assertEquals(1_134L, WeightUnits.parse("2.5", WeightUnit.LB))
        assertEquals("37.5", WeightUnits.format(37_500, WeightUnit.KG))
        assertThrows(WorkoutException::class.java) { WeightUnits.parse("-1", WeightUnit.LB) }
    }

    @Test fun pausesAndClockChangesCannotProduceNegativeDurationOrRest() {
        val start = Instant.parse("2026-10-28T01:00:00Z")
        val session = WorkoutSession("s", start, LocalDate.parse("2026-10-28"), SessionStatus.PAUSED,
            pausedAtUtc = start.plusSeconds(90), pausedDurationMillis = 30_000, restEndsAtUtc = start.plusSeconds(120))
        val workout = Workout(session, emptyList())
        assertEquals(60L, workout.elapsedDuration(start.plusSeconds(180)).seconds)
        assertEquals(0L, workout.elapsedDuration(start.minusSeconds(100)).seconds)
        assertEquals(0L, workout.restRemainingSeconds(start.plusSeconds(200)))
        assertEquals(30L, workout.restRemainingSeconds(start.plusSeconds(90)))
        assertEquals(3600L, workout.copy(session = session.copy(restEndsAtUtc = Instant.ofEpochMilli(Long.MAX_VALUE)))
            .restRemainingSeconds(Instant.ofEpochMilli(Long.MIN_VALUE)))
    }

    @Test fun statisticsUseFinishedFactsAndDoNotOverflowVolume() {
        val now = Instant.parse("2026-10-28T01:00:00Z")
        fun workout(id: String, status: SessionStatus) = Workout(
            WorkoutSession(id, now, LocalDate.parse("2026-10-28"), status, endedAtUtc = now.plusSeconds(60)),
            listOf(SessionExercise("e-$id", id, "x", 0, "动作", 2, 8, 12, listOf(
                SetRecord("1-$id", "e-$id", 1, Long.MAX_VALUE, 10, SetStatus.COMPLETED, now),
                SetRecord("2-$id", "e-$id", 2, 10_000, 20, SetStatus.PLANNED),
            ))),
        )
        val records = listOf(workout("a", SessionStatus.FINISHED), workout("b", SessionStatus.FINISHED), workout("c", SessionStatus.ACTIVE))
        val statistics = TrainingHistory.statistics(records)
        assertEquals(1, statistics.days)
        assertEquals(2, statistics.completedSets)
        assertEquals(120L, statistics.durationSeconds)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(20), statistics.volumeGrams)
        assertEquals(2, TrainingHistory.days(records).single().completedSets)
        assertEquals(2, TrainingHistory.performance(records, "x").size)
    }
}
