package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DailyWorkoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "daily-" + UUID.randomUUID()
    private lateinit var db: FitLogDatabase
    private lateinit var repo: RoomWorkoutRepository
    private val clock = object: Clock() {
        var now = Instant.parse("2026-11-03T08:00:00Z")
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    private val date get() = LocalDate.now(clock)
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FitLogDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Before fun setup() = runBlocking {
        db = FitLogDatabase.create(context, name); ExerciseCatalog(context, db).initialize()
        repo = RoomWorkoutRepository(db, clock)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun day() = repo.observeDailyWorkout(date).first()!!
    private suspend fun template() = RoomTrainingRepository(db, clock).saveTemplate(TemplateDraft(name = "今日推日",
        exercises = listOf(TemplateExercise("a", "barbell-bench-press", 0, 3, 8, 12, 37_500))))
    @Test fun reservationDoesNotStartTimerAndMultipleStopsKeepOneRecordAndAllDrafts() = runBlocking {
        val reserved = repo.reserveDailyWorkout(template())
        assertEquals(SessionStatus.READY, reserved.session.status)
        assertNull(repo.observeActiveWorkout().first())
        clock.now = clock.now.plusSeconds(3600)
        assertEquals(0L, day().elapsedDuration(clock.instant()).seconds)
        val started = repo.startDailyWorkout()
        val e = started.exercises.single()
        repo.completeSet(e.id, e.sets[0].id, 40_000, 10)
        repo.updateSetDraft(e.id, e.sets[1].id, 42_500, 9)
        repo.addExercise(started.session.id, "lat-pulldown", 1, 8, 12)
        clock.now = clock.now.plusSeconds(30)
        repo.finishWorkout(started.session.id, "保留感想")
        clock.now = clock.now.plusSeconds(7200)
        val resumed = repo.startDailyWorkout()
        assertEquals(reserved.session.id, resumed.session.id)
        assertEquals(1, resumed.completedSetCount)
        assertEquals(42_500L, resumed.exercises.first().sets[1].weightGrams)
        assertEquals(30L, resumed.elapsedDuration(clock.instant()).seconds)
        assertEquals("保留感想", resumed.session.feedback)
        assertEquals(2, resumed.exercises.size)
        assertEquals(1, TrainingHistory.statistics(listOf(resumed)).completedSets)
        clock.now = clock.now.plusSeconds(20)
        repo.finishWorkout(resumed.session.id, null)
        assertEquals(50L, day().elapsedDuration(clock.instant()).seconds)
        assertEquals(1, db.trainingDao().allSessions().size)
        assertEquals("保留感想", day().session.feedback)
    }
    @Test fun finishedActionsCanGainSetsAndOnlyExplicitExtraCreatesAnotherRecord() = runBlocking {
        val w = repo.startDailyWorkout(exerciseIds = listOf("barbell-bench-press"))
        val e = w.exercises.single()
        for(set in e.sets) repo.completeSet(e.id, set.id, 30_000, 8, finishWhenAllDone = true)
        assertEquals(SessionStatus.FINISHED, day().session.status)
        repo.addDailySet(e.id)
        assertEquals(SessionStatus.FINISHED, day().session.status)
        assertEquals(3, day().completedSetCount)
        val resumed = repo.startDailyWorkout()
        assertEquals(w.session.id, resumed.session.id)
        assertEquals(4, resumed.plannedSetCount)
        val fourth = resumed.exercises.single().sets.last()
        repo.completeSet(e.id, fourth.id, 32_500, 8, finishWhenAllDone = true)
        val extra = repo.startWorkout(exerciseIds = listOf("plank"))
        assertTrue(extra.session.isExtra)
        assertEquals(w.session.id, day().session.id)
        assertFalse(day().session.isExtra)
        assertEquals(4, day().completedSetCount)
        assertEquals(2, db.trainingDao().allSessions().size)
    }
    @Test fun removalKeepsCompletedFactsAndRenumbersGapsWithoutTouchingTemplate() = runBlocking {
        val t = template()
        val w = repo.startDailyWorkout(t)
        val e = w.exercises.single()
        repo.completeSet(e.id, e.sets[1].id, 40_000, 8)
        repo.completeSet(e.id, e.sets[2].id, 42_500, 8)
        val completedIds = day().exercises.single().sets.drop(1).map { it.id }.toSet()
        repo.removeExercise(e.id)
        val kept = day().exercises.single()
        assertTrue(kept.removedFromPlan)
        assertEquals(completedIds, kept.sets.map { it.id }.toSet())
        assertEquals(listOf(1, 2), kept.sets.map { it.setIndex })
        assertEquals(2, kept.targetSetCountSnapshot)
        assertEquals(3, RoomTrainingRepository(db).observeTemplates().first().single().exercises.single().targetSetCount)
        repo.addDailySet(e.id)
        assertFalse(day().exercises.single().removedFromPlan)
        repo.addDailyExercise("plank")
        val other = day().exercises.last()
        repo.removeExercise(other.id)
        assertEquals(1, day().exercises.size)
        assertEquals(2, day().completedSetCount)
        val json = JsonBackupRepository(db).exportJson()
        JsonBackupRepository(db).importJson(json)
        Unit
    }
    @Test fun backupRetainsDailyIdentityAndReservationAndRejectsDuplicateDates() = runBlocking {
        repo.reserveDailyWorkout(template())
        repo.addDailyExercise("plank")
        val json = JsonBackupRepository(db).exportJson()
        val restoredName = "daily-restored-" + UUID.randomUUID()
        val restored = FitLogDatabase.create(context, restoredName)
        try {
            JsonBackupRepository(restored).importJson(json)
            val r = RoomWorkoutRepository(restored, clock)
            assertEquals(day().session.id, r.observeDailyWorkout(date).first()!!.session.id)
            assertEquals(2, r.startDailyWorkout().exercises.size)
            val malformed = JSONObject(json)
            val sessions = malformed.getJSONObject("data").getJSONArray("workout_sessions")
            sessions.put(JSONObject(sessions.getJSONObject(0).toString()).put("id", "another"))
            try { JsonBackupRepository(restored).importJson(malformed.toString()); fail("duplicate day") } catch(_: IllegalArgumentException) {}
        } finally { restored.close(); context.deleteDatabase(restoredName) }
    }
    @Test fun concurrentStartsAdoptLegacyRecordAndYesterdayRemainsReadOnly() = runBlocking {
        val legacy = repo.startWorkout(exerciseIds = listOf("barbell-bench-press"))
        val e = legacy.exercises.single()
        repo.completeSet(e.id, e.sets.first().id, 30_000, 8)
        repo.finishWorkout(legacy.session.id, null)
        val ids = (1..8).map { async { repo.startDailyWorkout().session.id } }.awaitAll()
        assertEquals(setOf(legacy.session.id), ids.toSet())
        repo.finishWorkout(legacy.session.id, null)
        clock.now = clock.now.plusSeconds(86400)
        val next = repo.startDailyWorkout(exerciseIds = listOf("plank"))
        assertNotEquals(legacy.session.id, next.session.id)
        try { repo.addDailySet(e.id); fail("yesterday changed") } catch(_: WorkoutException) {}
        assertEquals(1, repo.observeWorkout(legacy.session.id).first()!!.completedSetCount)
    }
    @Test fun versionSixUpgradeKeepsOrdinaryRecordsAndCanAdoptThemExplicitly() = runBlocking {
        val oldName = "daily-migration-" + UUID.randomUUID()
        helper.createDatabase(oldName, 6).apply {
            execSQL("INSERT INTO workout_sessions(id,startedAtUtc,localWorkoutDate,status,endedAtUtc,unfinishedSlot,pausedDurationMillis) VALUES('old',1000,'2026-11-03','FINISHED',2000,NULL,0)")
            close()
        }
        helper.runMigrationsAndValidate(oldName, 7, true, DatabaseMigrations.FROM_6_TO_7).close()
        val migrated = FitLogDatabase.create(context, oldName)
        try {
            val r = RoomWorkoutRepository(migrated, clock)
            assertNull(r.observeWorkout("old").first()!!.session.dailyPlanDate)
            assertEquals("old", r.reserveDailyWorkout().session.id)
            assertEquals(date, r.observeWorkout("old").first()!!.session.dailyPlanDate)
        } finally { migrated.close(); context.deleteDatabase(oldName) }
    }
}
