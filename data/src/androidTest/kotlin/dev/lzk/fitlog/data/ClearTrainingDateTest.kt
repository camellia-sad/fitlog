package dev.lzk.fitlog.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClearTrainingDateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "clear-date-" + UUID.randomUUID()
    private lateinit var db: FitLogDatabase
    private lateinit var training: RoomTrainingRepository
    private val date = LocalDate.parse("2026-11-03")
    private fun repo(day: LocalDate) = RoomWorkoutRepository(db, Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
    @Before fun setup() = runBlocking<Unit> {
        db = FitLogDatabase.create(context, name); ExerciseCatalog(context, db).initialize()
        training = RoomTrainingRepository(db)
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    @Test fun deletionIsDateScopedCascadesAndAllowsAFreshDailyPlan() = runBlocking<Unit> {
        val custom = training.createCustomExercise(CustomExerciseDraft("保留自定义", equipment = EquipmentType.DUMBBELL, primaryMuscles = setOf(Muscle.CHEST)))
        val template = training.saveTemplate(TemplateDraft(name = "保留模板", exercises = listOf(TemplateExercise("a", custom, 0))))
        training.saveTemplateDraft(TemplateDraft(name = "保留草稿"))
        training.savePlan(TrainingPlan(date, templateIds = listOf(template) + List(6) { null }))
        training.setFavorite(custom, true)
        training.setDefaultWeight(custom, 25_000)
        val previous = repo(date.minusDays(1))
        val old = previous.startWorkout(template)
        val oldExercise = old.exercises.single()
        previous.completeSet(oldExercise.id, oldExercise.sets.first().id, 20_000, 8)
        val savedOld = previous.finishWorkout(old.session.id, "保留昨日感想")
        val current = repo(date)
        val daily = current.startDailyWorkout(template)
        val e = daily.exercises.single()
        current.completeSet(e.id, e.sets.first().id, 30_000, 8)
        current.updateSetDraft(e.id, e.sets[1].id, 35_000, 10)
        current.finishWorkout(daily.session.id, "当天感想")
        val extra = current.startWorkout(exerciseIds = listOf("plank"))
        training.setDefaultWeight(custom, 25_000)
        assertEquals(2, training.clearTrainingDate(date))
        assertNull(current.observeWorkout(daily.session.id).first())
        assertNull(current.observeWorkout(extra.session.id).first())
        assertNull(current.observeActiveWorkout().first())
        assertNull(current.observeDailyWorkout(date).first())
        assertEquals(savedOld, previous.observeWorkout(old.session.id).first())
        assertEquals(listOf(old.session.id), db.trainingDao().allSessionExercises().map { it.sessionId }.distinct())
        assertEquals(3, db.trainingDao().allSets().size)
        assertEquals(0, training.clearTrainingDate(date))
        assertEquals(template, training.observeTemplates().first().single().id)
        assertEquals("保留草稿", training.observeTemplateDrafts().first().single().name)
        assertEquals(template, training.observePlan().first()!!.templateIds.first())
        val definition = current.observeExercises().first().single { it.id == custom }
        assertTrue(definition.isCustom); assertTrue(definition.isFavorite)
        assertEquals(25_000L, definition.defaultWeightGrams)
        val history = training.observeHistory(date.minusDays(1), date).first()
        assertEquals(1, TrainingHistory.statistics(history).completedSets)
        assertEquals(listOf(date.minusDays(1)), TrainingHistory.days(history).map { it.date })
        val reset = current.startDailyWorkout(template)
        assertNotEquals(daily.session.id, reset.session.id)
        assertFalse(reset.session.isExtra)
        assertEquals(0, reset.completedSetCount)
    }
    @Test fun clearingAReservationPreservesAnotherDatesOngoingTraining() = runBlocking<Unit> {
        val current = repo(date)
        val ready = current.reserveDailyWorkout(exerciseIds = listOf("barbell-bench-press"))
        assertEquals(SessionStatus.READY, ready.session.status)
        val old = repo(date.minusDays(1)).startWorkout(exerciseIds = listOf("plank"))
        assertEquals(1, training.clearTrainingDate(date))
        assertNull(current.observeDailyWorkout(date).first())
        assertEquals(old, current.observeActiveWorkout().first())
        assertEquals(0, training.clearTrainingDate(date.plusDays(1)))
        assertEquals(1, db.trainingDao().allSessions().size)
    }
}
