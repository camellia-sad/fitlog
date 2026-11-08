package dev.lzk.fitlog.data

import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Day records: each template trained today has one record and free training shares one more. Switching to another
 * record stops the running one (its completed sets stay) or drops it when nothing was completed; only an explicit
 * start reopens a stopped record, excluding the stopped stretch from its duration.
 */
internal class RoomDailyWorkouts(
    private val db: FitLogDatabase, private val clock: Clock, private val repository: RoomWorkoutRepository,
) {
    private val dao = db.workoutDao()
    private fun today() = LocalDate.now(clock).toString()
    private fun slot(templateId: String?) = templateId ?: ""
    private suspend fun record(id: String) = requireNotNull(dao.workout(id)).toDomain()

    /** Creates today's record for the slot without starting its timer (kept for older flows and tests). */
    suspend fun reserve(templateId: String?, exerciseIds: List<String>): Workout = db.withTransaction {
        val date = today()
        dao.daySession(date, slot(templateId))?.let { return@withTransaction record(it.id) }
        WorkoutRules.input(dao.activeSession() == null, "请先结束当前训练")
        val created = repository.startWorkout(templateId, exerciseIds)
        val row = requireNotNull(dao.session(created.session.id))
        dao.updateSession(row.copy(status = "READY", unfinishedSlot = null, dailyPlanDate = date, daySlot = slot(templateId)))
        record(row.id)
    }

    suspend fun start(templateId: String?, exerciseIds: List<String>): Workout = db.withTransaction {
        val date = today()
        val existing = dao.daySession(date, slot(templateId))
        leave(except = existing?.id)
        if (existing == null) {
            val created = repository.startWorkout(templateId, exerciseIds)
            val row = requireNotNull(dao.session(created.session.id))
            dao.updateSession(row.copy(dailyPlanDate = date, daySlot = slot(templateId)))
            return@withTransaction record(row.id)
        }
        val day = record(existing.id)
        if (templateId != null && existing.status == "FINISHED" && day.plannedSetCount > 0 && day.allSetsCompleted && exerciseIds.isEmpty())
            return@withTransaction day
        val now = maxOf(clock.millis(), existing.endedAtUtc ?: existing.startedAtUtc)
        when (existing.status) {
            "READY" -> dao.updateSession(existing.copy(startedAtUtc = now, status = "ACTIVE", unfinishedSlot = 1))
            "FINISHED" -> dao.updateSession(existing.copy(status = "ACTIVE", unfinishedSlot = 1, endedAtUtc = null,
                pausedAtUtc = null, restEndsAtUtc = null,
                pausedDurationMillis = Math.addExact(existing.pausedDurationMillis, now - requireNotNull(existing.endedAtUtc))))
            "PAUSED" -> repository.resumeWorkout(existing.id)
            "ACTIVE" -> Unit
            else -> throw WorkoutException(WorkoutError.SESSION_ENDED, "该记录无法继续")
        }
        val existingIds = record(existing.id).exercises.filterNot { it.removedFromPlan }.map { it.exerciseId }.toSet()
        exerciseIds.distinct().filterNot { it in existingIds }.forEach { id ->
            val source = dao.exercise(id) ?: throw WorkoutException(WorkoutError.NOT_FOUND, "动作不存在")
            val metric = RecordMetric.valueOf(source.recordMetric)
            repository.addExercise(existing.id, id, WorkoutRules.DEFAULT_SET_COUNT, metric.defaultMin, metric.defaultMax)
        }
        record(existing.id)
    }

    /** Stops the running session unless it is [except]; one without a completed set leaves no record behind. */
    private suspend fun leave(except: String?) {
        val active = dao.activeSession() ?: return
        if (active.id == except) return
        if (record(active.id).completedSetCount == 0) dao.deleteSession(active.id)
        else repository.finishWorkout(active.id, null)
    }

    /** Today's finished record back to training for an edit; the stopped stretch is excluded like any explicit continue. */
    suspend fun reopen(sessionId: String) = db.withTransaction {
        val row = dao.session(sessionId) ?: throw WorkoutException(WorkoutError.NOT_FOUND, "会话不存在")
        WorkoutRules.input(row.dailyPlanDate == today(), "只能修改今天的训练记录")
        if (row.status == "ACTIVE") return@withTransaction
        WorkoutRules.input(row.status == "FINISHED", "该记录无法继续")
        leave(except = row.id)
        val now = maxOf(clock.millis(), requireNotNull(row.endedAtUtc))
        dao.updateSession(row.copy(status = "ACTIVE", unfinishedSlot = 1, endedAtUtc = null, pausedAtUtc = null, restEndsAtUtc = null,
            pausedDurationMillis = Math.addExact(row.pausedDurationMillis, now - requireNotNull(row.endedAtUtc))))
    }

    suspend fun addAction(sessionId: String, exerciseId: String) = db.withTransaction {
        val session = dao.session(sessionId) ?: throw WorkoutException(WorkoutError.NOT_FOUND, "会话不存在")
        WorkoutRules.input(session.dailyPlanDate == today() && session.status in listOf("READY", "ACTIVE", "PAUSED", "FINISHED"), "该记录无法修改")
        val source = dao.exercise(exerciseId) ?: error("动作不存在")
        WorkoutRules.input(!source.isArchived, "动作已归档")
        val metric = RecordMetric.valueOf(source.recordMetric)
        val id = UUID.randomUUID().toString()
        dao.insertSessionExercise(SessionExerciseEntity(id, session.id, exerciseId, dao.nextExerciseOrder(session.id),
            source.nameZh, WorkoutRules.DEFAULT_SET_COUNT, metric.defaultMin, metric.defaultMax, recordMetricSnapshot = source.recordMetric))
        val weight = if(metric == RecordMetric.SECONDS) 0L else source.effectiveWeight(dao.bestWeight(exerciseId)) ?: 0L
        dao.insertSets((1..WorkoutRules.DEFAULT_SET_COUNT).map {
            SetEntity(UUID.randomUUID().toString(), id, it, weight, metric.defaultMin)
        })
    }

    suspend fun addSet(exerciseId: String) = db.withTransaction {
        val exercise = requireNotNull(dao.sessionExercise(exerciseId)) { "动作不存在" }
        val session = requireNotNull(dao.session(exercise.sessionId))
        WorkoutRules.input(session.dailyPlanDate == today(), "只能为今天的训练增加目标组")
        WorkoutRules.input(session.status in listOf("READY", "ACTIVE", "PAUSED", "FINISHED"), "该记录无法修改")
        val sets = dao.sets(exerciseId)
        WorkoutRules.setCount(sets.size + 1)
        val last = sets.lastOrNull()
        dao.insertSets(listOf(SetEntity(UUID.randomUUID().toString(), exerciseId, sets.size + 1,
            last?.weightGrams ?: 0L, last?.reps ?: exercise.targetRepMinSnapshot)))
        dao.updateSessionExercise(exercise.copy(targetSetCountSnapshot = sets.size + 1, removedFromPlan = false))
    }

    suspend fun remove(exerciseId: String) = db.withTransaction {
        val exercise = requireNotNull(dao.sessionExercise(exerciseId)) { "动作不存在" }
        val session = requireNotNull(dao.session(exercise.sessionId))
        WorkoutRules.input((session.dailyPlanDate == today() && session.status in listOf("READY", "ACTIVE", "PAUSED", "FINISHED")) || session.status == "ACTIVE", "该记录无法修改")
        val completed = dao.sets(exerciseId).filter { it.status == "COMPLETED" }
        if(completed.isEmpty()) dao.deleteExercise(exerciseId)
        else {
            dao.deleteUnfinishedSets(exerciseId)
            completed.forEachIndexed { index, set -> dao.updateSet(set.copy(setIndex = index + 1)) }
            dao.updateSessionExercise(exercise.copy(targetSetCountSnapshot = completed.size, removedFromPlan = true))
        }
    }
}
