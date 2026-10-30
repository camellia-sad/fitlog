package dev.lzk.fitlog.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomWorkoutRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "fitlog-repository-test.db"
    private lateinit var db: FitLogDatabase
    private lateinit var repository: RoomWorkoutRepository
    private val clock = TestClock()
    private val bench = "barbell-bench-press"

    @Before fun setup() = runBlocking {
        context.deleteDatabase(databaseName)
        reopen()
        ExerciseCatalog(context, db).initialize()
    }

    @After fun teardown() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    private fun reopen() {
        db = FitLogDatabase.create(context, databaseName)
        repository = RoomWorkoutRepository(db, clock)
    }

    private suspend fun workout(id: String) = requireNotNull(repository.observeWorkout(id).first())

    private suspend fun add(sessionId: String, exercise: String = bench, count: Int = 3): SessionExercise {
        val id = AddExerciseToSessionUseCase(repository)(sessionId, exercise, count)
        return workout(sessionId).exercises.first { it.id == id }
    }

    @Test fun catalogIsSearchableRelationalAndIdempotent() = runBlocking {
        ExerciseCatalog(context, db).initialize()
        assertEquals(42, repository.observeExercises().first().size)
        assertEquals(bench, repository.observeExercises("Barbell Bench").first().single().id)
        assertTrue(repository.observeExercises("卧推").first().size >= 2)
        val filtered = repository.observeExercises("", EquipmentType.BARBELL, Muscle.CHEST).first()
        assertEquals(listOf(bench), filtered.map { it.id })
        assertTrue(repository.observeExercises("%").first().isEmpty())
    }

    @Test fun concurrentStartReturnsTheSameUnfinishedSession() = runBlocking {
        val ids = (1..10).map { async { repository.startWorkout().session.id } }.awaitAll()
        assertEquals(1, ids.distinct().size)
    }

    @Test fun rapidRepeatedCompletionCreatesOneFact() = runBlocking {
        val session = repository.startWorkout()
        val exercise = add(session.session.id)
        val set = exercise.sets.first()
        val complete = CompleteSetUseCase(repository)
        val results = (1..20).map { async { complete(exercise.id, set.id, 42_500, 10) } }.awaitAll()
        assertEquals(1, results.count { it is CompletionResult.Completed })
        assertEquals(19, results.count { it is CompletionResult.AlreadyCompleted })
        val saved = workout(session.session.id)
        assertEquals(1, saved.completedSetCount)
        assertEquals(3, saved.exercises.single().sets.size)
        assertEquals(42_500L, saved.exercises.single().sets.first().weightGrams)
        val repeated = complete(exercise.id, set.id, 50_000, 8)
        assertEquals(42_500L, repeated.record.weightGrams)
    }

    @Test fun draftsAndThreeExercisesSurviveDatabaseReopen() = runBlocking {
        val sessionId = repository.startWorkout().session.id
        val first = add(sessionId)
        val second = add(sessionId, "lat-pulldown")
        val third = add(sessionId, "barbell-squat")
        repository.completeSet(first.id, first.sets[0].id, 40_000, 10)
        repository.completeSet(first.id, first.sets[1].id, 42_500, 8)
        repository.updateSetDraft(second.id, second.sets[0].id, 37_500, 11)
        repository.completeSet(third.id, third.sets[0].id, 60_000, 6)
        db.close()
        reopen()
        val restored = requireNotNull(repository.observeActiveWorkout().first())
        assertEquals(sessionId, restored.session.id)
        assertEquals(3, restored.exercises.size)
        assertEquals(3, restored.completedSetCount)
        val draft = restored.exercises[1].sets.first()
        assertEquals(37_500L, draft.weightGrams)
        assertEquals(11, draft.reps)
        assertEquals(SetStatus.PLANNED, draft.status)
        assertNull(draft.completedAtUtc)
        assertEquals(2, restored.exercises[0].sets.count { it.status == SetStatus.COMPLETED })
    }

    @Test fun invalidInputsAndMismatchedSetCannotWrite() = runBlocking {
        val id = repository.startWorkout().session.id
        val first = add(id)
        val second = add(id, "push-up")
        expect(WorkoutError.INVALID_INPUT) { repository.completeSet(first.id, first.sets[0].id, -1, 8) }
        expect(WorkoutError.INVALID_INPUT) { repository.completeSet(first.id, first.sets[0].id, 0, 0) }
        expect(WorkoutError.INVALID_INPUT) { repository.completeSet(second.id, first.sets[0].id, 0, 8) }
        expect(WorkoutError.INVALID_INPUT) { repository.adjustSetCount(first.id, 0, emptySet()) }
        expect(WorkoutError.INVALID_INPUT) { repository.adjustSetDraft(first.id, first.sets[0].id, -1, 0) }
        assertEquals(0, workout(id).completedSetCount)
        assertEquals(6, workout(id).plannedSetCount)
    }

    @Test fun zeroLoadCompletionAndUndoPreserveInputs() = runBlocking {
        val id = repository.startWorkout().session.id
        val exercise = add(id, "push-up")
        val setId = exercise.sets.first().id
        repository.completeSet(exercise.id, setId, 0, 15)
        assertEquals(1, workout(id).completedSetCount)
        expect(WorkoutError.SET_NOT_EDITABLE) { repository.updateSetDraft(exercise.id, setId, 1_000, 10) }
        repository.undoCompletedSet(exercise.id, setId)
        repository.undoCompletedSet(exercise.id, setId)
        val saved = workout(id).exercises.single().sets.first()
        assertEquals(15, saved.reps)
        assertEquals(0L, saved.weightGrams)
        assertEquals(SetStatus.PLANNED, saved.status)
        assertNull(saved.completedAtUtc)
    }

    @Test fun completedSetRemovalRequiresCurrentConfirmation() = runBlocking {
        val id = repository.startWorkout().session.id
        val exercise = add(id)
        repository.completeSet(exercise.id, exercise.sets[2].id, 40_000, 8)
        val first = repository.adjustSetCount(exercise.id, 1, emptySet()) as SetCountResult.ConfirmationRequired
        assertEquals(setOf(exercise.sets[2].id), first.completedSetIds)
        assertEquals(3, workout(id).plannedSetCount)
        // Completing another trailing set invalidates the previous confirmation.
        repository.completeSet(exercise.id, exercise.sets[1].id, 40_000, 8)
        val renewed = repository.adjustSetCount(exercise.id, 1, first.completedSetIds) as SetCountResult.ConfirmationRequired
        assertEquals(2, renewed.completedSetIds.size)
        assertEquals(3, workout(id).plannedSetCount)
        assertEquals(SetCountResult.Updated, repository.adjustSetCount(exercise.id, 1, renewed.completedSetIds))
        assertEquals(1, workout(id).plannedSetCount)
        assertEquals(0, workout(id).completedSetCount)
        repository.adjustSetCount(exercise.id, 4, emptySet())
        assertEquals(listOf(1, 2, 3, 4), workout(id).exercises.single().sets.map { it.setIndex })
    }

    @Test fun draftDeltaUpdatesAreAtomicAndOverflowRollsBack() = runBlocking {
        val id = repository.startWorkout().session.id
        val exercise = add(id)
        val setId = exercise.sets.first().id
        repository.updateSetDraft(exercise.id, setId, 40_000, 8)
        (1..10).map { async { repository.adjustSetDraft(exercise.id, setId, 2_500, 1) } }.awaitAll()
        val saved = workout(id).exercises.single().sets.first()
        assertEquals(65_000L, saved.weightGrams)
        assertEquals(18, saved.reps)
        repository.updateSetDraft(exercise.id, setId, Long.MAX_VALUE, Int.MAX_VALUE)
        expect(WorkoutError.INVALID_INPUT) { repository.adjustSetDraft(exercise.id, setId, 1, 0) }
        expect(WorkoutError.INVALID_INPUT) { repository.adjustSetDraft(exercise.id, setId, 0, 1) }
        assertEquals(Long.MAX_VALUE, workout(id).exercises.single().sets.first().weightGrams)
    }

    @Test fun finishIsIdempotentAndRejectsOrdinaryTrainingWrites() = runBlocking {
        val id = repository.startWorkout().session.id
        val exercise = add(id)
        val setId = exercise.sets.first().id
        repository.completeSet(exercise.id, setId, 40_000, 8)
        clock.time = clock.time.plusSeconds(120)
        val finished = repository.finishWorkout(id, "完成")
        assertEquals(SessionStatus.FINISHED, finished.session.status)
        assertEquals(120L, finished.elapsedDuration(clock.instant()).seconds)
        assertEquals(finished, repository.finishWorkout(id, "不能覆盖"))
        expect(WorkoutError.SESSION_NOT_ACTIVE) { repository.completeSet(exercise.id, exercise.sets[1].id, 40_000, 8) }
        expect(WorkoutError.SESSION_NOT_ACTIVE) { repository.undoCompletedSet(exercise.id, setId) }
        expect(WorkoutError.SESSION_NOT_ACTIVE) { repository.updateSetDraft(exercise.id, setId, 0, 0) }
        expect(WorkoutError.SESSION_NOT_ACTIVE) { repository.adjustSetCount(exercise.id, 1, emptySet()) }
        expect(WorkoutError.SESSION_NOT_ACTIVE) { add(id) }
        assertNull(repository.observeActiveWorkout().first())
    }

    @Test fun lastPerformanceIgnoresDraftsAndPrefillsWithoutCompleting() = runBlocking {
        val firstId = repository.startWorkout().session.id
        val first = add(firstId)
        repository.completeSet(first.id, first.sets[0].id, 42_500, 10)
        repository.updateSetDraft(first.id, first.sets[1].id, 90_000, 20)
        repository.finishWorkout(firstId, null)
        clock.time = clock.time.plusSeconds(100)
        val secondId = repository.startWorkout().session.id
        val second = add(secondId)
        assertEquals(42_500L, second.sets.first().weightGrams)
        assertTrue(second.sets.all { it.status == SetStatus.PLANNED && it.completedAtUtc == null })
        val previous = requireNotNull(repository.getLastExercisePerformance(bench, secondId))
        assertEquals(firstId, previous.sessionId)
        assertEquals(1, previous.sets.size)
        assertEquals(10, previous.sets.single().reps)
        assertEquals(0, workout(secondId).completedSetCount)
    }

    @Test fun twoSessionsOnOneLocalDateStayIndependent() = runBlocking {
        val first = repository.startWorkout()
        repository.finishWorkout(first.session.id, null)
        clock.time = clock.time.plusSeconds(100)
        val second = repository.startWorkout()
        assertNotEquals(first.session.id, second.session.id)
        assertEquals(first.session.localWorkoutDate, second.session.localWorkoutDate)
        assertEquals(SessionStatus.FINISHED, workout(first.session.id).session.status)
        assertEquals(SessionStatus.ACTIVE, workout(second.session.id).session.status)
    }

    @Test fun renamedAndArchivedExerciseDoesNotChangeSnapshot() = runBlocking {
        val id = repository.startWorkout().session.id
        val exercise = add(id)
        db.openHelper.writableDatabase.execSQL("UPDATE exercises SET nameZh = '新名称', isArchived = 1 WHERE id = ?", arrayOf(bench))
        assertEquals("杠铃卧推", workout(id).exercises.single().exerciseNameSnapshot)
        assertTrue(repository.observeExercises("Barbell Bench").first().isEmpty())
        expect(WorkoutError.INVALID_INPUT) { add(id) }
        repository.completeSet(exercise.id, exercise.sets.first().id, 40_000, 8)
        assertEquals(1, workout(id).completedSetCount)
    }

    @Test fun sqlConstraintsRejectInvalidNumbersMissingTimestampAndDuplicateIndex() = runBlocking {
        val id = repository.startWorkout().session.id
        val exercise = add(id)
        val original = requireNotNull(db.workoutDao().set(exercise.sets.first().id))
        for (invalid in listOf(original.copy(weightGrams = -1), original.copy(status = "COMPLETED", completedAtUtc = null),
            original.copy(status = "COMPLETED", reps = 0, completedAtUtc = clock.millis()))) {
            try {
                db.workoutDao().updateSet(invalid)
                fail("SQL constraint should reject invalid record")
            } catch (_: SQLiteConstraintException) { }
        }
        try {
            db.workoutDao().insertSets(listOf(original.copy(id = "duplicate")))
            fail("Duplicate index should fail")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(0, workout(id).completedSetCount)
    }

    @Test fun backwardClockCannotCreateNegativeSessionDuration() = runBlocking {
        val session = repository.startWorkout()
        val exercise = add(session.session.id)
        clock.time = clock.time.minusSeconds(3600)
        repository.completeSet(exercise.id, exercise.sets.first().id, 0, 8)
        val finished = repository.finishWorkout(session.session.id, null)
        assertEquals(0L, finished.elapsedDuration(clock.instant()).seconds)
        assertTrue(finished.exercises.single().sets.first().completedAtUtc!! >= session.session.startedAtUtc)
    }

    private suspend fun expect(error: WorkoutError, action: suspend () -> Unit) {
        try { action(); fail("Expected $error") }
        catch (exception: WorkoutException) { assertEquals(error, exception.error) }
    }

    private class TestClock : Clock() {
        var time: Instant = Instant.parse("2026-10-28T01:00:00Z")
        override fun instant() = time
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }
}
