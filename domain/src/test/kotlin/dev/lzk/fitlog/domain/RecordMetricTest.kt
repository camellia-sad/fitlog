package dev.lzk.fitlog.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.math.BigInteger

class RecordMetricTest {
    @Test fun secondsAreNotRepetitionsOrWeightVolume() {
        val now=Instant.parse("2026-10-31T00:00:00Z")
        val set=SetRecord("set","exercise",1,50000,60,SetStatus.COMPLETED,now)
        val exercise=SessionExercise("exercise","session","plank",0,"平板支撑",1,30,60,listOf(set),
            recordMetricSnapshot=RecordMetric.SECONDS)
        val workout=Workout(WorkoutSession("session",now.minusSeconds(60),LocalDate.parse("2026-10-31"),SessionStatus.FINISHED,now),listOf(exercise))
        assertEquals("60 秒",set.displayValue(RecordMetric.SECONDS))
        assertEquals(BigInteger.ZERO,TrainingHistory.statistics(listOf(workout)).volumeGrams)
        assertEquals(1,TrainingHistory.statistics(listOf(workout)).completedSets)
        assertEquals(RecordMetric.SECONDS,TrainingHistory.performance(listOf(workout),"plank").single().recordMetric)
        assertTrue(workout.socialPostText().contains("60 秒"))
        assertFalse(workout.socialPostText().contains("kg"))
    }
}
