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
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanPostponeAndRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "postpone-" + UUID.randomUUID()
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

    @Test fun versionEightPlansMigrateWithNoPostponedDays() = runBlocking {
        val file = "postpone-migration-" + UUID.randomUUID()
        helper.createDatabase(file, 8).apply {
            execSQL("INSERT INTO training_plans(id,startDate,cycleDays,repeats) VALUES('current','2026-11-02',7,1)")
            execSQL("INSERT INTO plan_days(planId,dayIndex,templateId) VALUES('current',0,NULL),('current',1,NULL),('current',2,NULL),('current',3,NULL),('current',4,NULL),('current',5,NULL),('current',6,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(file, 9, true, DatabaseMigrations.FROM_8_TO_9).close()
        val migrated = FitLogDatabase.create(context, file)
        try {
            val plan = RoomTrainingRepository(migrated).observePlan().first()!!
            assertTrue(plan.postponedDates.isEmpty())
            assertEquals(LocalDate.of(2026, 11, 2), plan.startDate)
        } finally { migrated.close(); context.deleteDatabase(file) }
    }

    @Test fun postponedDaysAreSavedAndTravelThroughBackups() = runBlocking {
        val template = training.saveTemplate(TemplateDraft(name = "推日", exercises = listOf(TemplateExercise("a", bench, 0))))
        val start = LocalDate.of(2026, 11, 2)
        val plan = TrainingPlan(start, templateIds = List(7) { if (it % 2 == 0) template else null })
        val today = start.plusDays(4)
        training.savePlan(plan.postpone(today).postpone(today.plusDays(1)))
        val saved = training.observePlan().first()!!
        assertEquals(setOf(today, today.plusDays(1)), saved.postponedDates)
        assertEquals(template, saved.templateOn(today.plusDays(2)))

        val json = JsonBackupRepository(db).exportJson()
        assertEquals(9, JSONObject(json).getInt("schemaVersion"))
        val otherName = "postpone-b-" + UUID.randomUUID()
        val other = FitLogDatabase.create(context, otherName)
        try {
            ExerciseCatalog(context, other).initialize()
            JsonBackupRepository(other).importJson(json)
            assertEquals(saved.postponedDates, RoomTrainingRepository(other).observePlan().first()!!.postponedDates)
            // A v7 backup has no postponed days and still re-imports over the same plan.
            val legacy = JSONObject(json).put("schemaVersion", 7)
            legacy.getJSONObject("data").getJSONArray("training_plans").getJSONObject(0).remove("postponedDates")
            JsonBackupRepository(other).importJson(legacy.toString())
            assertEquals(saved.postponedDates, RoomTrainingRepository(other).observePlan().first()!!.postponedDates)
        } finally { other.close(); context.deleteDatabase(otherName) }
    }

    @Test fun customExercisesKeepAssistingMusclesAndSecondsMode() = runBlocking {
        val id = training.createCustomExercise(CustomExerciseDraft("单臂哑铃划船", "One-arm row", EquipmentType.DUMBBELL,
            setOf(Muscle.BACK), secondaryMuscles = setOf(Muscle.BICEPS)))
        val row = workouts.observeExercises().first().single { it.id == id }
        assertEquals(setOf(Muscle.BACK), row.primaryMuscles)
        assertEquals(setOf(Muscle.BICEPS), row.secondaryMuscles)
        val hold = training.createCustomExercise(CustomExerciseDraft("靠墙静蹲", "", EquipmentType.BODYWEIGHT, setOf(Muscle.QUADRICEPS),
            recordMetric = RecordMetric.SECONDS))
        assertEquals(RecordMetric.SECONDS, workouts.observeExercises().first().single { it.id == hold }.recordMetric)
        try {
            training.createCustomExercise(CustomExerciseDraft("冲突", "", EquipmentType.DUMBBELL, setOf(Muscle.BACK), secondaryMuscles = setOf(Muscle.BACK)))
            fail("muscle in both roles accepted")
        } catch (_: WorkoutException) { }
    }

    @Test fun previousBestIgnoresTheCurrentSessionAndUndoLowersTheRecord() = runBlocking {
        val first = workouts.startWorkout()
        val a = workouts.addExercise(first.session.id, bench, 2, 8, 12)
        val aSets = workouts.observeActiveWorkout().first()!!.exercises.single { it.id == a }.sets
        workouts.completeSet(a, aSets[0].id, 60_000, 8)
        workouts.finishWorkout(first.session.id, null)

        val second = workouts.startWorkout()
        val b = workouts.addExercise(second.session.id, bench, 2, 8, 12)
        val bSets = workouts.observeActiveWorkout().first()!!.exercises.single { it.id == b }.sets
        workouts.completeSet(b, bSets[0].id, 600_000, 6)
        assertEquals(60_000L, workouts.previousBestWeight(bench, second.session.id))
        assertEquals(600_000L, workouts.observeExercises().first().single { it.id == bench }.bestWeightGrams)
        workouts.undoCompletedSet(b, bSets[0].id)
        assertEquals(60_000L, workouts.observeExercises().first().single { it.id == bench }.bestWeightGrams)
    }

    @Test fun todaysFinishedPlanReopensForAnUndoWithoutCountingTheStop() = runBlocking {
        val template = training.saveTemplate(TemplateDraft(name = "推日", exercises = listOf(TemplateExercise("a", bench, 0))))
        val day = workouts.startDailyWorkout(template)
        val exercise = workouts.observeActiveWorkout().first()!!.exercises.single()
        exercise.sets.forEach { workouts.completeSet(exercise.id, it.id, 40_000, 10, finishWhenAllDone = true) }
        val finished = workouts.observeWorkout(day.session.id).first()!!
        assertEquals(SessionStatus.FINISHED, finished.session.status)
        assertEquals(SessionStatus.FINISHED, workouts.startDailyWorkout(template).session.status)

        workouts.reopenDailyWorkout(day.session.id)
        val reopened = workouts.observeActiveWorkout().first()!!
        assertEquals(day.session.id, reopened.session.id)
        assertEquals(SessionStatus.ACTIVE, reopened.session.status)
        assertTrue(reopened.session.pausedDurationMillis >= finished.session.pausedDurationMillis)
        workouts.undoCompletedSet(exercise.id, exercise.sets.last().id)
        assertEquals(exercise.sets.size - 1, workouts.observeActiveWorkout().first()!!.completedSetCount)

        // Reopening switches away from whatever else is running; an empty session leaves nothing behind.
        workouts.finishWorkout(day.session.id, null)
        val other = workouts.startWorkout()
        workouts.reopenDailyWorkout(day.session.id)
        assertEquals(day.session.id, workouts.observeActiveWorkout().first()!!.session.id)
        assertNull(workouts.observeWorkout(other.session.id).first())
    }
}
