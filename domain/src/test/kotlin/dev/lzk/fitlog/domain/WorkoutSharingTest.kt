package dev.lzk.fitlog.domain

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate

class WorkoutSharingTest {
    private fun workout():Workout {
        val start=Instant.parse("2026-10-30T10:00:00Z")
        return Workout(WorkoutSession("workout",start,LocalDate.of(2026,10,30),SessionStatus.FINISHED,
            endedAtUtc=start.plusSeconds(120),templateNameSnapshot="自己的模板"),listOf(
                SessionExercise("exercise","workout","bench",0,"卧推",3,8,12,listOf(
                    SetRecord("done","exercise",1,22500,10,SetStatus.COMPLETED),
                    SetRecord("draft","exercise",2,999000,99,SetStatus.PLANNED),
                    SetRecord("skipped","exercise",3,888000,88,SetStatus.SKIPPED)))))
    }
    @Test fun textContainsReflectionAndCompletedSetsWithoutLeakingDrafts() {
        val text=workout().socialPostText("今天状态不错")
        assertTrue(text.contains("自己的模板"))
        assertTrue(text.contains("未完成"))
        assertTrue(text.contains("今天状态不错"))
        assertTrue(text.contains("22.5 kg × 10"))
        assertFalse(text.contains("999"));assertFalse(text.contains("888"))
        assertTrue(text.contains("2 分钟"))
    }
    @Test fun cardCarriesOnlyCompletedSetsTheMuscleAndRecords() {
        val catalog=listOf(Exercise("bench","卧推","Bench",EquipmentType.BARBELL,setOf(Muscle.CHEST)))
        val card=workout().postWorkout(catalog,mapOf("done" to 20_000L),WeightUnit.KG,caption="冲",feeling="不错")
        val row=card.exercises.single()
        assertEquals(listOf(PostSet(22500,10)),row.sets)
        assertEquals(Muscle.CHEST,row.muscle)
        assertEquals(20_000L,row.previousBest)
        assertEquals(225_000L,card.volumeGrams)
        assertEquals(1,card.completedSets);assertEquals(3,card.totalSets);assertFalse(card.finished)
        assertEquals("自",card.stamp)
        assertEquals("冲",card.caption);assertEquals("不错",card.feeling);assertEquals(2,card.minutes)
    }
    @Test fun postIsBoundedAndActiveWorkoutCannotBeShared() {
        assertTrue(workout().socialPostText("很".repeat(8000)).length<=4000)
        val active=workout().copy(session=workout().session.copy(status=SessionStatus.ACTIVE))
        assertThrows(IllegalArgumentException::class.java){active.socialPostText()}
    }
}
