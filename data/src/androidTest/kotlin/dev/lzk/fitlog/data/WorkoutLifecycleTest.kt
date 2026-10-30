package dev.lzk.fitlog.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutLifecycleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "lifecycle-v3-test.db"
    private val restore = "lifecycle-v3-restore.db"
    private lateinit var db: FitLogDatabase
    private lateinit var repo: RoomWorkoutRepository
    private lateinit var training: RoomTrainingRepository
    private val clock = MutableClock()
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); context.deleteDatabase(restore)
        db = FitLogDatabase.create(context, name)
        ExerciseCatalog(context, db).initialize()
        repo = RoomWorkoutRepository(db, clock); training = RoomTrainingRepository(db, clock)
    }
    @After fun teardown() { db.close(); context.deleteDatabase(name); context.deleteDatabase(restore) }
    private suspend fun current() = repo.observeActiveWorkout().first()!!
    private suspend fun template() = training.saveTemplate(TemplateDraft(name = "推日", exercises = listOf(
        TemplateExercise("one", "barbell-bench-press", 0, 1, 8, 12, 37_500, 90))))

    @Test fun preparedStartIsAtomicAndOnlyCreatesSessionWhenRequested() = runBlocking {
        assertNull(repo.observeActiveWorkout().first())
        try { repo.startWorkout(exerciseIds = listOf("barbell-bench-press", "missing")); fail("Must roll back") }
        catch (_: WorkoutException) { }
        assertTrue(db.trainingDao().allSessions().isEmpty())
        val started = repo.startWorkout(exerciseIds = listOf("barbell-bench-press", "lat-pulldown"))
        assertEquals(2, started.exercises.size)
        assertEquals(0, started.completedSetCount)
        assertEquals(clock.instant(), started.session.startedAtUtc)
    }

    @Test fun finishingLastGroupAtomicallyStopsTimeAndDuplicateIsHarmless() = runBlocking {
        val started = repo.startWorkout(template())
        clock.time = clock.time.plusSeconds(10)
        repo.pauseWorkout(started.session.id)
        clock.time = clock.time.plusSeconds(60)
        assertEquals(10L, current().elapsedDuration(clock.instant()).seconds)
        repo.resumeWorkout(started.session.id)
        clock.time = clock.time.plusSeconds(10)
        val exercise = current().exercises.single()
        val set = exercise.sets.single()
        assertTrue(repo.completeSet(exercise.id, set.id, 37_500, 8, 90, true) is CompletionResult.Completed)
        assertNull(repo.observeActiveWorkout().first())
        val saved = repo.observeWorkout(started.session.id).first()!!
        assertEquals(SessionStatus.FINISHED, saved.session.status)
        assertTrue(saved.allSetsCompleted)
        assertNull(saved.session.restEndsAtUtc)
        assertEquals(20L, saved.elapsedDuration(clock.instant().plusSeconds(3600)).seconds)
        assertTrue(repo.completeSet(exercise.id, set.id, 90_000, 99, 90, true) is CompletionResult.AlreadyCompleted)
        assertEquals(37_500L, repo.observeWorkout(started.session.id).first()!!.exercises.single().sets.single().weightGrams)
        repo.saveFeedback(started.session.id, "坚持")
        assertEquals("坚持", repo.observeWorkout(started.session.id).first()!!.session.feedback)
    }

    @Test fun reducingGoalToCompletedGroupsAlsoFinishesAtomically() = runBlocking {
        val started = repo.startWorkout(exerciseIds = listOf("barbell-bench-press"))
        val exercise = started.exercises.single()
        repo.completeSet(exercise.id, exercise.sets.first().id, 0, 8, finishWhenAllDone = true)
        assertNotNull(repo.observeActiveWorkout().first())
        repo.adjustSetCount(exercise.id, 1, emptySet(), finishWhenAllDone = true)
        assertNull(repo.observeActiveWorkout().first())
        assertTrue(repo.observeWorkout(started.session.id).first()!!.allSetsCompleted)
    }

    @Test fun firstDailySessionKeepsSourceAndSubsequentSessionsAreExtra() = runBlocking {
        val first = repo.startWorkout()
        assertEquals("自由训练", first.session.trainingLabel)
        repo.finishWorkout(first.session.id, null)
        val second = repo.startWorkout(template())
        assertEquals("加练", second.session.trainingLabel)
        assertEquals("推日", second.session.templateNameSnapshot)
        repo.finishWorkout(second.session.id, null)
        clock.time = clock.time.plusSeconds(86400)
        assertEquals("推日", repo.startWorkout(training.observeTemplates().first().single().id).session.trainingLabel)
    }

    @Test fun plansRoundTripWithBackupsAndDeletedTemplatesBecomeRestDays() = runBlocking {
        val id = template()
        val plan = TrainingPlan(LocalDate.of(2026,10,26),14,true,List(14) { if (it == 0 || it == 13) id else null })
        training.savePlan(plan)
        val json = JsonBackupRepository(db).exportJson()
        val restored = FitLogDatabase.create(context, restore)
        try {
            JsonBackupRepository(restored).importJson(json)
            val target = RoomTrainingRepository(restored)
            assertEquals(plan, target.observePlan().first())
            target.deleteTemplate(id)
            assertTrue(target.observePlan().first()!!.templateIds.all { it == null })
        } finally { restored.close() }
    }

    @Test fun legacyBackupWithoutPlansStillImports() = runBlocking {
        val first = repo.startWorkout()
        repo.finishWorkout(first.session.id, null)
        val json = JSONObject(JsonBackupRepository(db).exportJson())
        json.put("schemaVersion", 1)
        json.getJSONObject("data").remove("training_plans")
        json.getJSONObject("data").remove("plan_days")
        val restored = FitLogDatabase.create(context, restore)
        try {
            JsonBackupRepository(restored).importJson(json.toString())
            assertEquals(1, restored.trainingDao().allSessions().size)
            assertNull(RoomTrainingRepository(restored).observePlan().first())
        } finally { restored.close() }
    }
    private class MutableClock : Clock() {
        var time = Instant.parse("2026-10-28T01:00:00Z")
        override fun instant() = time
        override fun getZone() = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time,zone)
    }
}
