package dev.lzk.fitlog.data

import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomWorkoutRepository(
    private val database: FitLogDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WorkoutRepository {
    private val dao = database.workoutDao()

    override fun observeExercises(query: String, equipment: EquipmentType?, muscle: Muscle?, archived: Boolean) =
        dao.observeExercises(query.trim(), equipment?.name, muscle?.name, archived).map { rows -> rows.map { it.toDomain() } }

    override fun observeActiveWorkout() = dao.observeActiveWorkout().map { it?.toDomain() }
    override fun observeWorkout(sessionId: String) = dao.observeWorkout(sessionId).map { it?.toDomain() }

    override suspend fun startWorkout(templateId: String?, exerciseIds: List<String>): Workout = database.withTransaction {
        dao.activeSession()?.let {
            if (templateId != null) throw WorkoutException(WorkoutError.SESSION_NOT_ACTIVE, "请先结束当前训练，再启动模板")
            return@withTransaction workout(it.id)
        }
        val template = templateId?.let {
            database.trainingDao().template(it) ?: throw WorkoutException(WorkoutError.NOT_FOUND, "模板不存在")
        }
        val now = clock.instant()
        WorkoutRules.input(now.toEpochMilli() >= 0, "设备时间无效")
        val date = LocalDate.ofInstant(now, clock.zone).toString()
        val beganAt = maxOf(now.toEpochMilli(), dao.lastStart(date)?.let { Math.addExact(it, 1) } ?: 0)
        WorkoutRules.input(templateId == null || exerciseIds.isEmpty(), "模板和自由训练动作不能混用")
        WorkoutRules.input(exerciseIds.size <= 30, "一次最多准备 30 个动作")
        val session = SessionEntity(newId(), beganAt, date, "ACTIVE",
            templateId = templateId, templateNameSnapshot = template?.template?.name)
        dao.insertSession(session)
        template?.exercises?.sortedBy { it.sortOrder }?.forEachIndexed { index, item ->
            val source = dao.exercise(item.exerciseId) ?: missing("模板动作不存在")
            WorkoutRules.input(!source.isArchived, "模板包含已归档动作，请先修改模板")
            WorkoutRules.input(source.recordMetric!="SECONDS" || item.defaultWeightGrams==0L,"时长动作不记录重量")
            val id = newId()
            dao.insertSessionExercise(SessionExerciseEntity(id, session.id, source.id, index, source.nameZh,
                item.targetSetCount, item.targetRepMin, item.targetRepMax, item.restSeconds,source.recordMetric))
            val weight = if(source.recordMetric == "SECONDS") 0L else if(item.useExerciseWeight) defaultWeight(source) else item.defaultWeightGrams
            dao.insertSets((1..item.targetSetCount).map { setIndex ->
                SetEntity(newId(), id, setIndex, weight, item.targetRepMin)
            })
        }
        exerciseIds.forEach {
            val source=dao.exercise(it) ?: missing("动作不存在")
            val metric=RecordMetric.valueOf(source.recordMetric)
            addExercise(session.id,it,WorkoutRules.DEFAULT_SET_COUNT,metric.defaultMin,metric.defaultMax)
        }
        workout(session.id)
    }

    override suspend fun addExercise(sessionId: String, exerciseId: String, setCount: Int, repMin: Int, repMax: Int): String =
        database.withTransaction {
            WorkoutRules.setCount(setCount)
            WorkoutRules.repRange(repMin, repMax)
            activeSession(sessionId)
            val source = dao.exercise(exerciseId) ?: missing("动作不存在")
            WorkoutRules.input(!source.isArchived, "动作已归档")
            val previous = dao.lastPerformance(exerciseId, sessionId)?.takeIf {it.exercise.recordMetricSnapshot==source.recordMetric}?.sets
                ?.filter { it.status == "COMPLETED" }?.sortedBy { it.setIndex }.orEmpty()
            val id = newId()
            dao.insertSessionExercise(SessionExerciseEntity(
                id, sessionId, exerciseId, dao.nextExerciseOrder(sessionId), source.nameZh, setCount, repMin, repMax,
                recordMetricSnapshot=source.recordMetric,
            ))
            val weight = defaultWeight(source)
            dao.insertSets((1..setCount).map { index ->
                val reference = previous.getOrNull(index - 1) ?: previous.lastOrNull()
                SetEntity(newId(), id, index, weight, reference?.reps ?: repMin)
            })
            id
        }

    override suspend fun updateSetDraft(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int) {
        database.withTransaction {
            WorkoutRules.draft(weightGrams, reps)
            val record = editableSet(sessionExerciseId, setId)
            validateMetric(requireNotNull(dao.sessionExercise(sessionExerciseId)),weightGrams)
            dao.updateSet(record.copy(weightGrams = weightGrams, reps = reps))
        }
    }

    override suspend fun adjustSetDraft(sessionExerciseId: String, setId: String, weightDeltaGrams: Long, repsDelta: Int) {
        database.withTransaction {
            val record = editableSet(sessionExerciseId, setId)
            val weight: Long
            val reps: Int
            try {
                weight = Math.addExact(record.weightGrams, weightDeltaGrams)
                reps = Math.addExact(record.reps, repsDelta)
            } catch (e: ArithmeticException) {
                throw WorkoutException(WorkoutError.INVALID_INPUT, "调整后的数值超出范围")
            }
            WorkoutRules.draft(weight, reps)
            validateMetric(requireNotNull(dao.sessionExercise(sessionExerciseId)),weight)
            dao.updateSet(record.copy(weightGrams = weight, reps = reps))
        }
    }

    override suspend fun adjustSetCount(sessionExerciseId: String, count: Int, confirmedCompletedSetIds: Set<String>, finishWhenAllDone: Boolean): SetCountResult =
        database.withTransaction {
            WorkoutRules.setCount(count)
            val exercise = activeExercise(sessionExerciseId)
            val sets = dao.sets(sessionExerciseId)
            if (count == sets.size) return@withTransaction SetCountResult.Updated
            val completedToRemove = sets.filter { it.setIndex > count && it.status == "COMPLETED" }.map { it.id }.toSet()
            if (completedToRemove.isNotEmpty() && completedToRemove != confirmedCompletedSetIds) {
                return@withTransaction SetCountResult.ConfirmationRequired(completedToRemove, count)
            }
            if (count < sets.size) {
                dao.deleteTrailingSets(sessionExerciseId, count)
            } else {
                val reference = sets.lastOrNull()
                dao.insertSets((sets.size + 1..count).map { index ->
                    SetEntity(newId(), sessionExerciseId, index, reference?.weightGrams ?: 0L, reference?.reps ?: exercise.targetRepMinSnapshot)
                })
            }
            dao.updateSessionExercise(exercise.copy(targetSetCountSnapshot = count))
            if (finishWhenAllDone && workout(exercise.sessionId).allSetsCompleted) finishWorkout(exercise.sessionId, null)
            SetCountResult.Updated
        }

    override suspend fun changeSetCount(sessionExerciseId: String, delta: Int, finishWhenAllDone: Boolean): SetCountResult = database.withTransaction {
        val exercise = activeExercise(sessionExerciseId)
        val count = try { Math.addExact(exercise.targetSetCountSnapshot, delta) }
        catch (error: ArithmeticException) { throw WorkoutException(WorkoutError.INVALID_INPUT, "组数超出范围") }
        adjustSetCount(sessionExerciseId, count, emptySet(), finishWhenAllDone)
    }

    override suspend fun completeSet(sessionExerciseId: String, setId: String, weightGrams: Long, reps: Int, restSeconds: Int, finishWhenAllDone: Boolean): CompletionResult =
        database.withTransaction {
            WorkoutRules.completion(weightGrams, reps)
            WorkoutRules.rest(restSeconds)
            val source = dao.sessionExercise(sessionExerciseId) ?: missing("会话动作不存在")
            validateMetric(source,weightGrams)
            val record = ownedSet(source.id, setId)
            val owner = dao.session(source.sessionId) ?: missing("会话不存在")
            if (finishWhenAllDone && record.status == "COMPLETED" && owner.status == "FINISHED")
                return@withTransaction CompletionResult.AlreadyCompleted(record.toDomain())
            val exercise = activeExercise(sessionExerciseId)
            if (record.status == "COMPLETED") return@withTransaction CompletionResult.AlreadyCompleted(record.toDomain())
            if (record.status != "PLANNED") throw WorkoutException(WorkoutError.SET_NOT_EDITABLE, "该组无法完成")
            val session = dao.session(exercise.sessionId) ?: missing("会话不存在")
            val completedAt = maxOf(clock.millis(), session.startedAtUtc)
            val changed = dao.completePlannedSet(setId, weightGrams, reps, completedAt)
            if (changed == 1) {
                dao.updateSession(session.copy(restEndsAtUtc = if (restSeconds > 0) Math.addExact(completedAt, restSeconds * 1000L) else null))
            }
            if (finishWhenAllDone && workout(session.id).allSetsCompleted) finishWorkout(session.id, null)
            val saved = dao.set(setId) ?: missing("组记录不存在")
            if (changed == 1) CompletionResult.Completed(saved.toDomain()) else CompletionResult.AlreadyCompleted(saved.toDomain())
        }

    override suspend fun undoCompletedSet(sessionExerciseId: String, setId: String) {
        database.withTransaction {
            activeExercise(sessionExerciseId)
            val record = ownedSet(sessionExerciseId, setId)
            if (record.status == "COMPLETED") dao.updateSet(record.copy(status = "PLANNED", completedAtUtc = null))
        }
    }

    override suspend fun pauseWorkout(sessionId: String) {
        database.withTransaction {
            val session = dao.session(sessionId) ?: missing("会话不存在")
            if (session.status == "PAUSED") return@withTransaction
            WorkoutRules.active(SessionStatus.valueOf(session.status))
            dao.updateSession(session.copy(status = "PAUSED", pausedAtUtc = maxOf(clock.millis(), session.startedAtUtc)))
        }
    }

    override suspend fun resumeWorkout(sessionId: String) {
        database.withTransaction {
            val session = dao.session(sessionId) ?: missing("会话不存在")
            if (session.status == "ACTIVE") return@withTransaction
            if (session.status != "PAUSED") throw WorkoutException(WorkoutError.SESSION_ENDED, "会话已结束")
            val extra = (clock.millis() - requireNotNull(session.pausedAtUtc)).coerceAtLeast(0)
            dao.updateSession(session.copy(status = "ACTIVE", pausedAtUtc = null,
                pausedDurationMillis = Math.addExact(session.pausedDurationMillis, extra)))
        }
    }

    override suspend fun skipRest(sessionId: String) {
        database.withTransaction {
            val session = dao.session(sessionId) ?: missing("会话不存在")
            if (session.status !in listOf("ACTIVE", "PAUSED")) throw WorkoutException(WorkoutError.SESSION_ENDED, "会话已结束")
            dao.updateSession(session.copy(restEndsAtUtc = null))
        }
    }

    override suspend fun finishWorkout(sessionId: String, feedback: String?): Workout = database.withTransaction {
        val session = dao.session(sessionId) ?: missing("会话不存在")
        if (session.status == "FINISHED") return@withTransaction workout(sessionId)
        if (session.status !in listOf("ACTIVE", "PAUSED")) throw WorkoutException(WorkoutError.SESSION_ENDED, "会话已结束")
        val end = maxOf(clock.millis(), session.startedAtUtc)
        val pause = session.pausedAtUtc?.let { (end - it).coerceAtLeast(0) } ?: 0
        dao.updateSession(session.copy(
            status = "FINISHED", endedAtUtc = end, feedback = feedback?.trim()?.ifBlank { null },
            unfinishedSlot = null, restEndsAtUtc = null, pausedAtUtc = null,
            pausedDurationMillis = Math.addExact(session.pausedDurationMillis, pause),
        ))
        workout(sessionId)
    }

    override suspend fun saveFeedback(sessionId: String, feedback: String?) {
        database.withTransaction {
            val session = dao.session(sessionId) ?: missing("会话不存在")
            WorkoutRules.input(session.status == "FINISHED", "请先结束训练")
            WorkoutRules.input(feedback.orEmpty().length <= 2000, "感想最多 2000 字")
            dao.updateSession(session.copy(feedback = feedback?.trim()?.ifBlank { null }))
        }
    }

    override suspend fun getLastExercisePerformance(exerciseId: String, excludingSessionId: String?): ExercisePerformance? =
        database.withTransaction {
            val previous = dao.lastPerformance(exerciseId, excludingSessionId) ?: return@withTransaction null
            val session = dao.session(previous.exercise.sessionId) ?: missing("会话不存在")
            ExercisePerformance(session.id, exerciseId, session.toDomain().startedAtUtc,
                previous.sets.filter { it.status == "COMPLETED" }.sortedBy { it.setIndex }.map { it.toDomain() })
        }

    private suspend fun defaultWeight(exercise: ExerciseEntity): Long =
        if(exercise.recordMetric == "SECONDS") 0L else exercise.effectiveWeight(dao.bestWeight(exercise.id)) ?: 0L

    private suspend fun workout(id: String) = dao.workout(id)?.toDomain() ?: missing("会话不存在")

    private suspend fun activeSession(id: String): SessionEntity {
        val session = dao.session(id) ?: missing("会话不存在")
        WorkoutRules.active(SessionStatus.valueOf(session.status))
        return session
    }

    private suspend fun activeExercise(id: String): SessionExerciseEntity {
        val exercise = dao.sessionExercise(id) ?: missing("会话动作不存在")
        activeSession(exercise.sessionId)
        return exercise
    }

    private suspend fun ownedSet(exerciseId: String, setId: String): SetEntity {
        val set = dao.set(setId) ?: missing("组记录不存在")
        WorkoutRules.input(set.sessionExerciseId == exerciseId, "组记录不属于该会话动作")
        return set
    }

    private suspend fun editableSet(exerciseId: String, setId: String): SetEntity {
        activeExercise(exerciseId)
        val record = ownedSet(exerciseId, setId)
        if (record.status != "PLANNED") throw WorkoutException(WorkoutError.SET_NOT_EDITABLE, "请先撤销完成，再修改草稿")
        return record
    }

    private fun validateMetric(exercise:SessionExerciseEntity,weight:Long) {
        WorkoutRules.input(exercise.recordMetricSnapshot!="SECONDS" || weight==0L,"时长动作不记录重量")
    }
    private fun missing(message: String): Nothing = throw WorkoutException(WorkoutError.NOT_FOUND, message)
}
