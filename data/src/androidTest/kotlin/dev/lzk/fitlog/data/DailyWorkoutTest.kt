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
    private suspend fun records() = repo.observeDayRecords(date).first()
    private suspend fun template(name: String = "今日推日", exercise: String = "barbell-bench-press") = RoomTrainingRepository(db, clock).saveTemplate(TemplateDraft(name = name,
        exercises = listOf(TemplateExercise("a", exercise, 0, 3, 8, 12, 37_500))))

    @Test fun reservationDoesNotStartTimerAndMultipleStopsKeepOneRecordAndAllDrafts() = runBlocking {
        val t = template()
        val reserved = repo.reserveDailyWorkout(t)
        assertEquals(SessionStatus.READY, reserved.session.status)
        assertNull(repo.observeActiveWorkout().first())
        clock.now = clock.now.plusSeconds(3600)
        assertEquals(0L, day().elapsedDuration(clock.instant()).seconds)
        val started = repo.startDailyWorkout(t)
        assertEquals(reserved.session.id, started.session.id)
        val e = started.exercises.single()
        repo.completeSet(e.id, e.sets[0].id, 40_000, 10)
        repo.updateSetDraft(e.id, e.sets[1].id, 42_500, 9)
        repo.addExercise(started.session.id, "lat-pulldown", 1, 8, 12)
        clock.now = clock.now.plusSeconds(30)
        repo.finishWorkout(started.session.id, "保留感想")
        clock.now = clock.now.plusSeconds(7200)
        val resumed = repo.startDailyWorkout(t)
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

    @Test fun finishedTemplateGainsSetsStaysForReviewAndFreeTrainingBecomesExtra() = runBlocking {
        val t = template()
        val w = repo.startDailyWorkout(t)
        val e = w.exercises.single()
        for(set in e.sets) repo.completeSet(e.id, set.id, 30_000, 8, finishWhenAllDone = true)
        assertEquals(SessionStatus.FINISHED, day().session.status)
        // Choosing a finished template again opens it as it is.
        assertEquals(SessionStatus.FINISHED, repo.startDailyWorkout(t).session.status)
        assertNull(repo.observeActiveWorkout().first())
        repo.addDailySet(e.id)
        assertEquals(SessionStatus.FINISHED, day().session.status)
        assertEquals(3, day().completedSetCount)
        val resumed = repo.startDailyWorkout(t)
        assertEquals(w.session.id, resumed.session.id)
        assertEquals(4, resumed.plannedSetCount)
        repo.completeSet(e.id, resumed.exercises.single().sets.last().id, 32_500, 8, finishWhenAllDone = true)
        val free = repo.startDailyWorkout(exerciseIds = listOf("plank"))
        assertTrue(free.session.isExtra)
        assertEquals("加练", free.session.trainingLabel)
        assertEquals(listOf(w.session.id, free.session.id), records().map { it.session.id })
        assertFalse(records().first().session.isExtra)
        assertEquals(4, records().first().completedSetCount)
        // Free training has no fixed target, so finishing every set keeps it running.
        val plank = free.exercises.single()
        for (set in plank.sets) repo.completeSet(plank.id, set.id, 0, 30, finishWhenAllDone = true)
        assertEquals(SessionStatus.ACTIVE, repo.observeWorkout(free.session.id).first()!!.session.status)
        // Choosing free training again continues the same record.
        assertEquals(free.session.id, repo.startDailyWorkout().session.id)
    }

    @Test fun switchingStopsTheRunningRecordKeepingSetsAndDropsAnEmptyOne() = runBlocking {
        val push = template("推日")
        val pull = template("拉日", "lat-pulldown")
        val a = repo.startDailyWorkout(push)
        val e = a.exercises.single()
        repo.completeSet(e.id, e.sets[0].id, 40_000, 8)
        val b = repo.startDailyWorkout(pull)
        assertEquals(b.session.id, repo.observeActiveWorkout().first()!!.session.id)
        val stopped = repo.observeWorkout(a.session.id).first()!!
        assertEquals(SessionStatus.FINISHED, stopped.session.status)
        assertEquals(1, stopped.completedSetCount)
        // 拉日 has nothing completed, so leaving it leaves no record.
        val free = repo.startDailyWorkout()
        assertNull(repo.observeWorkout(b.session.id).first())
        assertEquals(listOf(a.session.id, free.session.id), records().map { it.session.id })
        // Stopping an empty record drops it too.
        repo.finishWorkout(free.session.id, null)
        assertEquals(listOf(a.session.id), records().map { it.session.id })
        val again = repo.startDailyWorkout(push)
        assertEquals(a.session.id, again.session.id)
        assertEquals(SessionStatus.ACTIVE, again.session.status)
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
        repo.addDailyExercise(w.session.id, "plank")
        val other = day().exercises.last()
        repo.removeExercise(other.id)
        assertEquals(1, day().exercises.size)
        assertEquals(2, day().completedSetCount)
        val json = JsonBackupRepository(db).exportJson()
        JsonBackupRepository(db).importJson(json)
        Unit
    }

    @Test fun backupRetainsDayRecordsAndRejectsDuplicateSlots() = runBlocking {
        val t = template()
        val reserved = repo.reserveDailyWorkout(t)
        repo.addDailyExercise(reserved.session.id, "plank")
        val json = JsonBackupRepository(db).exportJson()
        assertEquals(9, JSONObject(json).getInt("schemaVersion"))
        val restoredName = "daily-restored-" + UUID.randomUUID()
        val restored = FitLogDatabase.create(context, restoredName)
        try {
            JsonBackupRepository(restored).importJson(json)
            val r = RoomWorkoutRepository(restored, clock)
            assertEquals(day().session.id, r.observeDailyWorkout(date).first()!!.session.id)
            assertEquals(t, r.observeDailyWorkout(date).first()!!.session.daySlot)
            assertEquals(2, r.startDailyWorkout(t).exercises.size)
            val malformed = JSONObject(json)
            val sessions = malformed.getJSONObject("data").getJSONArray("workout_sessions")
            sessions.put(JSONObject(sessions.getJSONObject(0).toString()).put("id", "another"))
            try { JsonBackupRepository(restored).importJson(malformed.toString()); fail("duplicate slot") } catch(_: IllegalArgumentException) {}
            // Older backups get the slot from the template, or the free slot.
            val legacy = JSONObject(json).put("schemaVersion", 8)
            legacy.getJSONObject("data").getJSONArray("workout_sessions").getJSONObject(0).remove("daySlot")
            val legacyName = "daily-legacy-" + UUID.randomUUID()
            val legacyDb = FitLogDatabase.create(context, legacyName)
            try {
                JsonBackupRepository(legacyDb).importJson(legacy.toString())
                assertEquals(t, RoomWorkoutRepository(legacyDb, clock).observeDailyWorkout(date).first()!!.session.daySlot)
            } finally { legacyDb.close(); context.deleteDatabase(legacyName) }
        } finally { restored.close(); context.deleteDatabase(restoredName) }
    }

    @Test fun concurrentStartsShareOneFreeRecordAndYesterdayRemainsReadOnly() = runBlocking {
        val standalone = repo.startWorkout(exerciseIds = listOf("barbell-bench-press"))
        val e = standalone.exercises.single()
        repo.completeSet(e.id, e.sets.first().id, 30_000, 8)
        repo.finishWorkout(standalone.session.id, null)
        val ids = (1..8).map { async { repo.startDailyWorkout().session.id } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertNotEquals(standalone.session.id, ids.first())
        assertNull(repo.observeWorkout(standalone.session.id).first()!!.session.dailyPlanDate)
        clock.now = clock.now.plusSeconds(86400)
        val next = repo.startDailyWorkout(exerciseIds = listOf("plank"))
        assertNotEquals(ids.first(), next.session.id)
        try { repo.addDailySet(e.id); fail("yesterday changed") } catch(_: WorkoutException) {}
        assertEquals(1, repo.observeWorkout(standalone.session.id).first()!!.completedSetCount)
    }

    @Test fun versionNineUpgradeGivesExistingDayRecordsTheirSlot() = runBlocking {
        val oldName = "daily-migration-" + UUID.randomUUID()
        helper.createDatabase(oldName, 9).apply {
            execSQL("INSERT INTO workout_sessions(id,startedAtUtc,localWorkoutDate,status,endedAtUtc,unfinishedSlot,pausedDurationMillis,dailyPlanDate) VALUES('old',1000,'2026-11-03','FINISHED',2000,NULL,0,'2026-11-03')")
            execSQL("INSERT INTO workout_sessions(id,startedAtUtc,localWorkoutDate,status,endedAtUtc,unfinishedSlot,pausedDurationMillis) VALUES('plain',3000,'2026-11-03','FINISHED',4000,NULL,0)")
            close()
        }
        helper.runMigrationsAndValidate(oldName, 10, true, DatabaseMigrations.FROM_9_TO_10).close()
        val migrated = FitLogDatabase.create(context, oldName)
        try {
            val r = RoomWorkoutRepository(migrated, clock)
            assertEquals("", r.observeWorkout("old").first()!!.session.daySlot)
            assertNull(r.observeWorkout("plain").first()!!.session.daySlot)
            assertEquals(listOf("old"), r.observeDayRecords(date).first().map { it.session.id })
            assertEquals("old", r.startDailyWorkout().session.id)
        } finally { migrated.close(); context.deleteDatabase(oldName) }
    }
}
