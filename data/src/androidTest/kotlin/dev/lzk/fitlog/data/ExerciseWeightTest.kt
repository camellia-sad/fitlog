package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExerciseWeightTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "weights-" + UUID.randomUUID()
    private lateinit var db: FitLogDatabase
    private lateinit var workouts: RoomWorkoutRepository
    private lateinit var training: RoomTrainingRepository
    private val bench = "barbell-bench-press"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        FitLogDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Before fun setup() = runBlocking {
        db = FitLogDatabase.create(context, name)
        ExerciseCatalog(context, db).initialize()
        workouts = RoomWorkoutRepository(db); training = RoomTrainingRepository(db)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private suspend fun definition() = workouts.observeExercises().first().single { it.id == bench }
    private suspend fun add(count: Int = 3): SessionExercise {
        val session = workouts.startWorkout()
        val id = workouts.addExercise(session.session.id, bench, count, 8, 12)
        return workouts.observeActiveWorkout().first()!!.exercises.single { it.id == id }
    }
    private suspend fun finish() { workouts.finishWorkout(workouts.observeActiveWorkout().first()!!.session.id, null) }

    @Test fun onlyCompletedFactsSetThePeakAndBothEntryPathsPrefillIt() = runBlocking {
        val first = add()
        workouts.completeSet(first.id, first.sets[0].id, 40_000, 10)
        workouts.completeSet(first.id, first.sets[1].id, 50_000, 8)
        workouts.updateSetDraft(first.id, first.sets[2].id, 90_000, 10)
        assertEquals(50_000L, definition().bestWeightGrams)
        finish()
        val next = add()
        assertTrue(next.sets.all { it.weightGrams == 50_000L && it.status == SetStatus.PLANNED })
        workouts.completeSet(next.id, next.sets[0].id, 30_000, 12)
        finish()
        assertEquals(50_000L, definition().defaultWeightGrams)
        val template = training.saveTemplate(TemplateDraft(name = "跟随", exercises = listOf(TemplateExercise("a", bench, 0))))
        val snapshot = workouts.startWorkout(template)
        assertTrue(snapshot.exercises.single().sets.all { it.weightGrams == 50_000L })
    }

    @Test fun manualDeloadSurvivesUntilNewPeakAndUndoRestoresIt() = runBlocking {
        val first = add()
        workouts.completeSet(first.id, first.sets[0].id, 50_000, 8)
        training.setDefaultWeight(bench, 25_000)
        assertEquals(25_000L, definition().defaultWeightGrams)
        workouts.completeSet(first.id, first.sets[1].id, 40_000, 10)
        assertEquals(25_000L, definition().defaultWeightGrams)
        workouts.completeSet(first.id, first.sets[2].id, 55_000, 6)
        assertEquals(55_000L, definition().defaultWeightGrams)
        workouts.undoCompletedSet(first.id, first.sets[2].id)
        assertEquals(50_000L, definition().bestWeightGrams)
        assertEquals(25_000L, definition().defaultWeightGrams)
        training.setDefaultWeight(bench, null)
        assertEquals(50_000L, definition().defaultWeightGrams)
        workouts.completeSet(first.id, first.sets[1].id, 90_000, 1)
        assertEquals(50_000L, definition().bestWeightGrams)
        try { training.setDefaultWeight("plank", 1000); fail("timed weight") } catch(_: WorkoutException) {}
        try { training.setDefaultWeight(bench, -1); fail("negative weight") } catch(_: WorkoutException) {}
    }

    @Test fun templateCanFollowNewDefaultsOrExplicitlyKeepZeroAndFixedWeight() = runBlocking {
        training.setDefaultWeight(bench, 30_000)
        val id = training.saveTemplate(TemplateDraft(name = "不同来源", exercises = listOf(
            TemplateExercise("a", bench, 0),
            TemplateExercise("b", bench, 1, defaultWeightGrams = 0, useExerciseWeight = false),
            TemplateExercise("c", bench, 2, defaultWeightGrams = 42_500))))
        training.setDefaultWeight(bench, 35_000)
        val started = workouts.startWorkout(id)
        assertEquals(listOf(35_000L, 0L, 42_500L), started.exercises.map { it.sets.first().weightGrams })
        training.setDefaultWeight(bench, 20_000)
        assertEquals(35_000L, workouts.observeActiveWorkout().first()!!.exercises.first().sets.first().weightGrams)
        db.workoutDao().saveCatalogVersion(CatalogVersionEntity("builtin", 0))
        ExerciseCatalog(context, db).initialize()
        assertEquals(20_000L, definition().defaultWeightGrams)
    }

    @Test fun backupCarriesManualWeightAndTemplateSourceAndLegacyImportDoesNotEraseDefault() = runBlocking {
        training.setDefaultWeight(bench, 37_500)
        training.saveTemplate(TemplateDraft(name = "固定零", exercises = listOf(TemplateExercise("a", bench, 0, useExerciseWeight = false))))
        training.saveTemplateDraft(TemplateDraft(name = "草稿", exercises = listOf(TemplateExercise("draft", bench, 0, useExerciseWeight = false))))
        val json = JsonBackupRepository(db).exportJson()
        val restoreName = "restored-" + UUID.randomUUID()
        val restored = FitLogDatabase.create(context, restoreName)
        try {
            ExerciseCatalog(context, restored).initialize()
            val backup = JsonBackupRepository(restored)
            backup.importJson(json)
            val repo = RoomWorkoutRepository(restored)
            assertEquals(37_500L, repo.observeExercises().first().single { it.id == bench }.defaultWeightGrams)
            val templates = RoomTrainingRepository(restored)
            assertFalse(templates.observeTemplates().first().single().exercises.single().useExerciseWeight)
            assertFalse(templates.observeTemplateDrafts().first().single().exercises.single().useExerciseWeight)
            assertEquals(ImportResult(0, 0, 0), backup.importJson(json))
            val legacy = JSONObject(JsonBackupRepository(db).exportJson()).put("schemaVersion", 4)
            // No template records, avoiding a deliberately different legacy zero-weight target.
            listOf("workout_templates", "template_exercises", "template_drafts").forEach { legacy.getJSONObject("data").put(it, org.json.JSONArray()) }
            backup.importJson(legacy.toString())
            assertEquals(37_500L, repo.observeExercises().first().single { it.id == bench }.defaultWeightGrams)
            val malformed = JSONObject(json)
            malformed.getJSONObject("data").getJSONArray("exercises").getJSONObject(0).put("defaultWeightGrams", -1)
            try { backup.importJson(malformed.toString()); fail("invalid weight accepted") } catch(_: IllegalStateException) {} catch(_: IllegalArgumentException) {}
            assertEquals(37_500L, repo.observeExercises().first().single { it.id == bench }.defaultWeightGrams)
        } finally { restored.close(); context.deleteDatabase(restoreName) }
    }

    @Test fun versionFiveUpgradePreservesHistoryAndFixedWeightsAndEnablesUnsetWeights() = runBlocking {
        val oldName = "old-weights-" + UUID.randomUUID()
        helper.createDatabase(oldName, 5).apply {
            execSQL("INSERT INTO exercises VALUES('old','旧卧推','Old','BARBELL',NULL,NULL,NULL,0,0,0,'REPETITIONS')")
            execSQL("INSERT INTO workout_templates VALUES('t','旧模板','FREE',1000,1000)")
            execSQL("INSERT INTO template_exercises VALUES('a','t','old',0,1,8,12,0,90)")
            execSQL("INSERT INTO template_exercises VALUES('b','t','old',1,1,8,12,37500,90)")
            execSQL("INSERT INTO workout_sessions(id,startedAtUtc,localWorkoutDate,status,endedAtUtc,unfinishedSlot) VALUES('s',1000,'2026-10-30','FINISHED',2000,NULL)")
            execSQL("INSERT INTO session_exercises VALUES('e','s','old',0,'旧卧推',1,8,12,NULL,'REPETITIONS')")
            execSQL("INSERT INTO set_records VALUES('done','e',1,42500,10,'COMPLETED',1500,'WORKING')")
            close()
        }
        helper.runMigrationsAndValidate(oldName, 6, true, DatabaseMigrations.FROM_5_TO_6).close()
        val restored = FitLogDatabase.create(context, oldName)
        try {
            val definition = RoomWorkoutRepository(restored).observeExercises().first().single()
            assertEquals(42_500L, definition.defaultWeightGrams)
            assertEquals(42_500L, definition.bestWeightGrams)
            val items = RoomTrainingRepository(restored).observeTemplates().first().single().exercises
            assertTrue(items[0].useExerciseWeight); assertFalse(items[1].useExerciseWeight)
            val workout = RoomWorkoutRepository(restored).startWorkout("t")
            assertEquals(listOf(42_500L, 37_500L), workout.exercises.map { it.sets.first().weightGrams })
        } finally { restored.close(); context.deleteDatabase(oldName) }
    }
}
