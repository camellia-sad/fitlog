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
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecondsRecordingTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        FitLogDatabase::class.java.canonicalName!!,FrameworkSQLiteOpenHelperFactory())
    @Test fun versionFourRecordsKeepRepetitionUnitsAfterMigration()=runBlocking {
        val name="metric-migration.db"
        helper.createDatabase(name,4).apply {
            execSQL("INSERT INTO exercises VALUES('old','旧动作','Old','BARBELL',NULL,NULL,NULL,1,0,0)")
            execSQL("INSERT INTO workout_sessions(id,startedAtUtc,localWorkoutDate,status,endedAtUtc,unfinishedSlot) VALUES('s',1000,'2026-10-30','FINISHED',2000,NULL)")
            execSQL("INSERT INTO session_exercises(id,sessionId,exerciseId,sortOrder,exerciseNameSnapshot,targetSetCountSnapshot,targetRepMinSnapshot,targetRepMaxSnapshot) VALUES('e','s','old',0,'历史动作',1,8,12)")
            execSQL("INSERT INTO set_records VALUES('set','e',1,25000,10,'COMPLETED',1500,'WORKING')")
            close()
        }
        helper.runMigrationsAndValidate(name,5,true,DatabaseMigrations.FROM_4_TO_5).close()
        val db=FitLogDatabase.create(context,name)
        try {
            val workout=RoomWorkoutRepository(db).observeWorkout("s").first()!!
            assertEquals(RecordMetric.REPETITIONS,workout.exercises.single().recordMetricSnapshot)
            assertEquals(25_000L,workout.exercises.single().sets.single().weightGrams)
            assertEquals(10,workout.exercises.single().sets.single().reps)
            assertTrue(db.trainingDao().allExercises().single().isFavorite)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun plankDefaultsSnapshotHistoryAndBackupRemainSeconds()=runBlocking {
        val aName="seconds-"+UUID.randomUUID();val bName="seconds-"+UUID.randomUUID()
        val a=FitLogDatabase.create(context,aName);val b=FitLogDatabase.create(context,bName)
        try {
            ExerciseCatalog(context,a).initialize();ExerciseCatalog(context,b).initialize()
            val repo=RoomWorkoutRepository(a)
            val workout=repo.startWorkout(exerciseIds=listOf("plank"))
            val exercise=workout.exercises.single();val set=exercise.sets.first()
            assertEquals(RecordMetric.SECONDS,exercise.recordMetricSnapshot)
            assertEquals(30,exercise.targetRepMinSnapshot);assertEquals(60,exercise.targetRepMaxSnapshot)
            assertEquals(30,set.reps)
            try {repo.updateSetDraft(exercise.id,set.id,1000,45);fail("timed weight accepted")} catch(_:WorkoutException) {}
            repo.updateSetDraft(exercise.id,set.id,0,45)
            repo.completeSet(exercise.id,set.id,0,45)
            repo.finishWorkout(workout.session.id,"支撑")
            val definition=a.workoutDao().exercise("plank")!!
            a.trainingDao().updateExercise(definition.copy(recordMetric="REPETITIONS"))
            val saved=repo.observeWorkout(workout.session.id).first()!!
            assertEquals(RecordMetric.SECONDS,saved.exercises.single().recordMetricSnapshot)
            assertEquals(BigInteger.ZERO,TrainingHistory.statistics(listOf(saved)).volumeGrams)
            assertTrue(saved.socialPostText().contains("45 秒"))
            val json=JsonBackupRepository(a).exportJson()
            assertEquals(6,JSONObject(json).getInt("schemaVersion"))
            JsonBackupRepository(b).importJson(json)
            val restored=RoomWorkoutRepository(b).observeWorkout(workout.session.id).first()!!
            assertEquals(RecordMetric.SECONDS,restored.exercises.single().recordMetricSnapshot)
            assertEquals(45,TrainingHistory.performance(listOf(restored),"plank").single().reps)
            assertEquals(RecordMetric.SECONDS,TrainingHistory.performance(listOf(restored),"plank").single().recordMetric)
        } finally {a.close();b.close();context.deleteDatabase(aName);context.deleteDatabase(bName)}
    }
    @Test fun versionThreeJsonDefaultsToRepetitionsAndSecondsTemplatesRejectWeight()=runBlocking {
        val aName="legacy-unit-"+UUID.randomUUID();val bName="legacy-unit-"+UUID.randomUUID()
        val a=FitLogDatabase.create(context,aName);val b=FitLogDatabase.create(context,bName)
        try {
            ExerciseCatalog(context,a).initialize();ExerciseCatalog(context,b).initialize()
            val repo=RoomWorkoutRepository(a)
            val w=repo.startWorkout();val ex=repo.addExercise(w.session.id,"barbell-bench-press",1,8,12)
            val set=repo.observeActiveWorkout().first()!!.exercises.single().sets.single()
            repo.completeSet(ex,set.id,30_000,9);repo.finishWorkout(w.session.id,null)
            val json=JSONObject(JsonBackupRepository(a).exportJson()).put("schemaVersion",3)
            val data=json.getJSONObject("data")
            data.getJSONArray("exercises").objects().forEach {it.remove("recordMetric")}
            data.getJSONArray("session_exercises").objects().forEach {it.remove("recordMetricSnapshot")}
            JsonBackupRepository(b).importJson(json.toString())
            val restored=RoomWorkoutRepository(b).observeWorkout(w.session.id).first()!!
            assertEquals(RecordMetric.REPETITIONS,restored.exercises.single().recordMetricSnapshot)
            try {
                RoomTrainingRepository(b).saveTemplate(TemplateDraft(name="时长",exercises=listOf(
                    TemplateExercise("x","plank",0,1,30,60,1000))))
                fail("timed template weight accepted")
            } catch(_:WorkoutException) {}
            assertTrue(b.trainingDao().allTemplates().isEmpty())
        } finally {a.close();b.close();context.deleteDatabase(aName);context.deleteDatabase(bName)}
    }
}
