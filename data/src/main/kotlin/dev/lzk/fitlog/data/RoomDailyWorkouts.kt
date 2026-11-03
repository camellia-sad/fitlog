package dev.lzk.fitlog.data

import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** The daily record survives timer stops; only an explicit start reopens it for training writes. */
internal class RoomDailyWorkouts(
    private val db: FitLogDatabase, private val clock: Clock, private val repository: RoomWorkoutRepository,
) {
    private val dao = db.workoutDao()
    private fun today() = LocalDate.now(clock).toString()
    private suspend fun record(id: String) = requireNotNull(dao.workout(id)).toDomain()

    suspend fun reserve(templateId: String?, exerciseIds: List<String>): Workout = db.withTransaction {
        val date = today()
        dao.dailySession(date)?.let { return@withTransaction record(it.id) }
        // Adopt the first existing record for today, retaining its facts and original template.
        dao.firstOnDate(date)?.let {
            dao.updateSession(it.copy(dailyPlanDate = date))
            return@withTransaction record(it.id)
        }
        WorkoutRules.input(dao.activeSession() == null, "请先结束上一天尚未结束的训练")
        val created = repository.startWorkout(templateId, exerciseIds)
        val row = requireNotNull(dao.session(created.session.id))
        dao.updateSession(row.copy(status = "READY", unfinishedSlot = null, dailyPlanDate = date))
        record(row.id)
    }

    suspend fun start(templateId: String?, exerciseIds: List<String>): Workout = db.withTransaction {
        val day = reserve(templateId, exerciseIds)
        val row = requireNotNull(dao.session(day.session.id))
        val active = dao.activeSession()
        WorkoutRules.input(active == null || active.id == row.id, "请先结束当前加练，再继续今日计划")
        WorkoutRules.input(!day.allSetsCompleted || exerciseIds.isNotEmpty(), "今日计划已完成，可增加组数或主动开始加练")
        val now = maxOf(clock.millis(), row.endedAtUtc ?: row.startedAtUtc)
        when(row.status) {
            "READY" -> dao.updateSession(row.copy(startedAtUtc = now, status = "ACTIVE", unfinishedSlot = 1))
            "FINISHED" -> dao.updateSession(row.copy(status = "ACTIVE", unfinishedSlot = 1, endedAtUtc = null,
                pausedAtUtc = null, restEndsAtUtc = null,
                pausedDurationMillis = Math.addExact(row.pausedDurationMillis, now - requireNotNull(row.endedAtUtc))))
            "PAUSED" -> repository.resumeWorkout(row.id)
            "ACTIVE" -> Unit
            else -> throw WorkoutException(WorkoutError.SESSION_ENDED, "该记录无法继续")
        }
        val existingIds = record(row.id).exercises.filterNot { it.removedFromPlan }.map { it.exerciseId }.toSet()
        exerciseIds.distinct().filterNot { it in existingIds }.forEach { id ->
            val source = dao.exercise(id) ?: throw WorkoutException(WorkoutError.NOT_FOUND, "动作不存在")
            val metric = RecordMetric.valueOf(source.recordMetric)
            repository.addExercise(row.id, id, WorkoutRules.DEFAULT_SET_COUNT, metric.defaultMin, metric.defaultMax)
        }
        record(row.id)
    }

    suspend fun addAction(exerciseId: String) = db.withTransaction {
        val session = dao.dailySession(today()) ?: error("请先设置今日计划")
        WorkoutRules.input(session.status in listOf("READY", "ACTIVE", "PAUSED", "FINISHED"), "该记录无法修改")
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
        WorkoutRules.input(session.dailyPlanDate == today(), "只能为今日计划增加目标组")
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
