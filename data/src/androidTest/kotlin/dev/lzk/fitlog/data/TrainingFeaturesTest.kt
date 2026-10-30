package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainingFeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "training-features-test.db"
    private val restoreName = "training-restore-test.db"
    private lateinit var db: FitLogDatabase
    private lateinit var workouts: RoomWorkoutRepository
    private lateinit var training: RoomTrainingRepository
    private val clock = TestClock()
    private val bench = "barbell-bench-press"

    @Before fun setup() = runBlocking {
        context.deleteDatabase(name); context.deleteDatabase(restoreName)
        db = FitLogDatabase.create(context, name)
        workouts = RoomWorkoutRepository(db, clock)
        training = RoomTrainingRepository(db, clock)
        ExerciseCatalog(context, db).initialize()
    }
    @After fun teardown() {
        db.close(); context.deleteDatabase(name); context.deleteDatabase(restoreName)
    }
    private fun draft() = TemplateDraft(name = "推日", splitType = SplitType.PUSH_PULL_LEGS, exercises = listOf(
        TemplateExercise("a", bench, 0, 2, 8, 10, 37_500, 120),
        TemplateExercise("b", "lat-pulldown", 1, 3, 10, 12, 40_000, 90),
    ))
    private suspend fun current() = requireNotNull(workouts.observeActiveWorkout().first())

    @Test fun templateSnapshotSurvivesEditsDeletionAndReopen() = runBlocking {
        val id = training.saveTemplate(draft())
        val template = training.observeTemplates().first().single()
        val started = workouts.startWorkout(id)
        assertEquals(bench, started.exercises.first().exerciseId)
        assertEquals(37_500L, started.exercises.first().sets.first().weightGrams)
        assertEquals(120, started.exercises.first().restSecondsSnapshot)
        training.saveTemplate(TemplateDraft(id, "已修改", template.splitType, template.exercises.reversed()
            .map { it.copy(targetSetCount = 1, defaultWeightGrams = 0) }))
        assertEquals(2, current().exercises.first().sets.size)
        assertEquals("推日", current().session.templateNameSnapshot)
        workouts.completeSet(started.exercises.first().id, started.exercises.first().sets.first().id, 37_500, 9)
        workouts.finishWorkout(started.session.id, "不错")
        training.deleteTemplate(id)
        val saved = requireNotNull(workouts.observeWorkout(started.session.id).first())
        assertNull(saved.session.templateId)
        assertEquals("推日", saved.session.templateNameSnapshot)
        assertEquals(1, saved.completedSetCount)
        assertEquals("杠铃卧推", saved.exercises.first().exerciseNameSnapshot)
    }

    @Test fun timerStartsOnceAndPauseIntervalsPersist() = runBlocking {
        val start = workouts.startWorkout()
        val exerciseId = workouts.addExercise(start.session.id, bench, 2, 8, 12)
        val set = current().exercises.first().sets.first()
        clock.time = clock.time.plusSeconds(30)
        workouts.completeSet(exerciseId, set.id, 40_000, 8, 90)
        val end = current().session.restEndsAtUtc
        clock.time = clock.time.plusSeconds(10)
        workouts.completeSet(exerciseId, set.id, 50_000, 10, 180)
        assertEquals(end, current().session.restEndsAtUtc)
        assertEquals(80L, current().restRemainingSeconds(clock.instant()))
        workouts.pauseWorkout(start.session.id)
        workouts.pauseWorkout(start.session.id)
        clock.time = clock.time.plusSeconds(60)
        db.close()
        db = FitLogDatabase.create(context, name)
        workouts = RoomWorkoutRepository(db, clock)
        assertEquals(SessionStatus.PAUSED, current().session.status)
        assertEquals(40L, current().elapsedDuration(clock.instant()).seconds)
        workouts.resumeWorkout(start.session.id)
        clock.time = clock.time.plusSeconds(30)
        workouts.skipRest(start.session.id)
        assertNull(current().session.restEndsAtUtc)
        val finished = workouts.finishWorkout(start.session.id, null)
        assertEquals(70L, finished.elapsedDuration(clock.instant()).seconds)
    }

    @Test fun backupRestoresCompleteGraphAndRepeatImportIsIdempotent() = runBlocking {
        val custom = training.createCustomExercise(CustomExerciseDraft("我的动作", equipment = EquipmentType.DUMBBELL, primaryMuscles = setOf(Muscle.BICEPS)))
        training.setFavorite(bench, true)
        val template = training.saveTemplate(draft())
        val session = workouts.startWorkout(template)
        val exercise = session.exercises.first()
        workouts.completeSet(exercise.id, exercise.sets.first().id, 42_500, 10, 90)
        workouts.updateSetDraft(exercise.id, exercise.sets.last().id, 37_500, 8)
        workouts.pauseWorkout(session.session.id)
        val json = JsonBackupRepository(db).exportJson()
        val restored = FitLogDatabase.create(context, restoreName)
        try {
            val backup = JsonBackupRepository(restored)
            val result = backup.importJson(json)
            assertEquals(1, result.sessionsAdded)
            assertEquals(1, result.templatesAdded)
            assertEquals(43, result.exercisesAdded)
            val restoredWorkouts = RoomWorkoutRepository(restored, clock)
            val record = requireNotNull(restoredWorkouts.observeActiveWorkout().first())
            assertEquals(SessionStatus.PAUSED, record.session.status)
            assertEquals(42_500L, record.exercises.first().sets.first().weightGrams)
            assertEquals(37_500L, record.exercises.first().sets.last().weightGrams)
            assertEquals(1, record.completedSetCount)
            assertNotNull(restored.workoutDao().exercise(custom))
            assertTrue(restoredWorkouts.observeExercises("杠铃卧推").first().single().isFavorite)
            assertEquals(ImportResult(0, 0, 0), backup.importJson(json))
        } finally { restored.close() }
    }

    @Test fun malformedBackupCannotLeavePartialRecords() = runBlocking {
        val session = workouts.startWorkout(training.saveTemplate(draft()))
        val text = JsonBackupRepository(db).exportJson()
        val json = JSONObject(text)
        json.getJSONObject("data").getJSONArray("set_records").getJSONObject(0).put("sessionExerciseId", "missing")
        val restored = FitLogDatabase.create(context, restoreName)
        try {
            try { JsonBackupRepository(restored).importJson(json.toString()); fail("Expected invalid reference") }
            catch (_: IllegalArgumentException) { }
            assertTrue(restored.trainingDao().allExercises().isEmpty())
            assertTrue(restored.trainingDao().allTemplates().isEmpty())
            assertTrue(restored.trainingDao().allSessions().isEmpty())
        } finally { restored.close() }
        assertEquals(session.session.id, current().session.id)
    }

    @Test fun conflictingActiveSessionRejectsWholeMerge() = runBlocking {
        workouts.startWorkout(training.saveTemplate(draft()))
        val json = JsonBackupRepository(db).exportJson()
        val restored = FitLogDatabase.create(context, restoreName)
        try {
            ExerciseCatalog(context, restored).initialize()
            val local = RoomWorkoutRepository(restored, clock).startWorkout()
            try { JsonBackupRepository(restored).importJson(json); fail("Expected active session conflict") }
            catch (_: IllegalArgumentException) { }
            assertEquals(local.session.id, requireNotNull(RoomWorkoutRepository(restored, clock).observeActiveWorkout().first()).session.id)
            assertTrue(restored.trainingDao().allTemplates().isEmpty())
        } finally { restored.close() }
    }

    @Test fun numericAndVersionCorruptionAndDeepJsonAreRejected() = runBlocking {
        workouts.startWorkout(training.saveTemplate(draft()))
        val json = JSONObject(JsonBackupRepository(db).exportJson())
        json.getJSONObject("data").getJSONArray("set_records").getJSONObject(0).put("weightGrams", 0.5)
        try { JsonBackupRepository(db).importJson(json.toString()); fail("Fractional grams must fail") }
        catch (_: IllegalArgumentException) { }
        json.put("schemaVersion", 99)
        try { JsonBackupRepository(db).importJson(json.toString()); fail("Unknown schema must fail") }
        catch (_: IllegalArgumentException) { }
        try { JsonBackupRepository(db).importJson("[".repeat(40) + "]".repeat(40)); fail("Deep JSON must fail") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun catalogUpgradePreservesUserFlagsAndHistoricalNames() = runBlocking {
        val session = workouts.startWorkout()
        workouts.addExercise(session.session.id, bench, 1, 8, 12)
        training.setFavorite(bench, true)
        training.setArchived(bench, true)
        db.openHelper.writableDatabase.execSQL("UPDATE catalog_versions SET version=1 WHERE id='builtin'")
        db.openHelper.writableDatabase.execSQL("UPDATE exercises SET nameZh='旧名', instructions=NULL WHERE id=?", arrayOf(bench))
        ExerciseCatalog(context, db).initialize()
        val exercise = requireNotNull(db.workoutDao().exercise(bench))
        assertEquals("杠铃卧推", exercise.nameZh)
        assertNotNull(exercise.instructions)
        assertTrue(exercise.isFavorite && exercise.isArchived)
        assertEquals("杠铃卧推", current().exercises.single().exerciseNameSnapshot)
        training.setArchived(bench, false)
        assertEquals(42, workouts.observeExercises().first().size)
    }

    @Test fun preferencesPersistUnitsAndDoNotRewriteWeights() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val filename = "features-test.preferences_pb"
        context.deleteFile(filename)
        val store = UserPreferencesStore(context, scope, filename)
        try {
            val value = UserPreferences(setOf(EquipmentType.DUMBBELL), setOf(Muscle.CHEST), SplitType.UPPER_LOWER,
                60, WeightUnit.LB, 1134, false, 120, true, ThemeMode.DARK)
            store.save(value)
            assertEquals(value, store.preferences.first())
            val session = workouts.startWorkout(training.saveTemplate(draft()))
            assertEquals(37_500L, session.exercises.first().sets.first().weightGrams)
            store.save(value.copy(weightUnit = WeightUnit.KG))
            assertEquals(37_500L, current().exercises.first().sets.first().weightGrams)
        } finally { job.cancelAndJoin(); context.deleteFile(filename) }
    }

    @Test fun longHistoryQueriesAllSessionsAndOnlyCountsFinishedSets() = runBlocking<Unit> {
        val base = LocalDate.of(2025, 1, 1)
        db.withTransaction {
            db.trainingDao().importSessions((0 until 500).map { index ->
                SessionEntity("history-$index", clock.millis() + index, base.plusDays(index.toLong()).toString(), "FINISHED",
                    endedAtUtc = clock.millis() + index + 60_000, unfinishedSlot = null)
            })
            db.trainingDao().importSessionExercises((0 until 500).map { index ->
                SessionExerciseEntity("history-ex-$index", "history-$index", bench, 0, "杠铃卧推", 2, 8, 12)
            })
            db.workoutDao().insertSets((0 until 500).flatMap { index -> listOf(
                SetEntity("history-set-$index", "history-ex-$index", 1, 42_500, 10, "COMPLETED", clock.millis()),
                SetEntity("history-draft-$index", "history-ex-$index", 2, 90_000, 20),
            ) })
        }
        val began = android.os.SystemClock.elapsedRealtime()
        val records = training.observeHistory(LocalDate.of(2026,1,1), LocalDate.of(2026,12,31)).first()
        assertEquals(135, records.size)
        assertEquals(135, TrainingHistory.statistics(records).completedSets)
        assertEquals(135, TrainingHistory.days(records).size)
        assertEquals(500, training.observeExerciseHistory(bench).first().size)
        android.util.Log.i("FitLogHistoryQuery", "500-session history and yearly aggregation: ${android.os.SystemClock.elapsedRealtime() - began} ms")
    }

    private class TestClock : Clock() {
        var time = Instant.parse("2026-10-28T01:00:00Z")
        override fun instant() = time
        override fun getZone() = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }
}
