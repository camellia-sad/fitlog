package dev.lzk.fitlog.data

import android.database.Cursor
import androidx.room.withTransaction
import dev.lzk.fitlog.domain.*
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first

class JsonBackupRepository(private val database: FitLogDatabase,
    private val preferences: PreferencesRepository? = null, private val preparation: PreparationRepository? = null) : BackupRepository {
    companion object { const val MAX_BYTES = 32 * 1024 * 1024 }
    private val dao = database.trainingDao()
    private val tableNames = listOf("exercises", "exercise_muscles", "workout_templates",
        "template_exercises", "workout_sessions", "session_exercises", "set_records", "training_plans", "plan_days", "template_drafts")

    override suspend fun exportJson(): String = database.withTransaction {
        val data = JSONObject()
        for (table in tableNames) {
            val rows = JSONArray()
            database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    for (index in 0 until cursor.columnCount) {
                        val value: Any = when (cursor.getType(index)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            else -> error("不支持的数据库字段")
                        }
                        row.put(cursor.getColumnName(index), value)
                    }
                    rows.put(row)
                }
            }
            data.put(table, rows)
        }
        JSONObject().put("format", "FitLog").put("schemaVersion", 6).put("data", data).apply {
            preferences?.let {put("preferences",it.preferences.first().backupJson())}
            preparation?.let {put("preparedExercises",JSONArray(it.preparedExercises.first()))}
        }.toString()
    }

    override suspend fun importJson(json: String): ImportResult = importData(json,true)
    private class PreviewRollback:RuntimeException()
    suspend fun validateJson(json:String) {
        try {database.withTransaction {importData(json,false);throw PreviewRollback()}}
        catch(_:PreviewRollback) { }
    }
    private suspend fun importData(json:String,applyPreferences:Boolean):ImportResult {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份最大支持 32 MB" }
        var depth = 0
        var quoted = false
        var escaped = false
        json.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "备份结构嵌套过深" } }
                '}', ']' -> depth--
            }
        }
        val root = JSONObject(json)
        val version = root.integer("schemaVersion")
        require(root.getString("format") == "FitLog" && version in 1..6) { "不支持的备份版本" }
        val restoredPreferences = if(version>=3) root.optJSONObject("preferences")?.backupPreferences() else null
        val prepared = if(version>=3) root.optJSONArray("preparedExercises")?.strings() else null
        require(prepared==null || prepared.size<=30 && prepared.distinct().size==prepared.size) {"待开始动作无效"}
        val data = root.getJSONObject("data")
        val drafts = if(version>=3) data.rows("template_drafts") {
            TemplateDraftEntity(it.text("id"),it.optionalText("templateId"),it.text("content"))
        } else emptyList()
        require(drafts.distinctBy {it.id}.size==drafts.size) {"模板草稿重复"}
        drafts.forEach {row->
            require(row.id==templateDraftKey(row.templateId) && row.content.length<=1_000_000) {"模板草稿无效"}
            val draft=row.toDraft()
            require(draft.name.length<=200 && draft.exercises.size<=30) {"模板草稿过大"}
            draft.exercises.forEach {
                WorkoutRules.setCount(it.targetSetCount);WorkoutRules.repRange(it.targetRepMin,it.targetRepMax)
                WorkoutRules.draft(it.defaultWeightGrams,0);WorkoutRules.rest(it.restSeconds)
            }
        }
        val incoming = Bundle(
            data.rows("exercises") { ExerciseEntity(it.text("id"), it.text("nameZh"), it.text("nameEn"), it.text("equipmentType"),
                it.optionalText("instructions"), it.optionalInt("estimatedSecondsPerSet"), it.optionalText("mediaAssetPath"),
                it.flag("isFavorite"), it.flag("isCustom"), it.flag("isArchived"),
                if(version>=4) it.text("recordMetric") else "REPETITIONS",
                if(version>=5) it.optionalLong("defaultWeightGrams") else null,
                if(version>=5) it.number("defaultWeightRecordGrams") else 0L) },
            data.rows("exercise_muscles") { ExerciseMuscleEntity(it.text("exerciseId"), it.text("muscle"), it.text("role")) },
            data.rows("workout_templates") { TemplateEntity(it.text("id"), it.text("name"), it.text("splitType"), it.number("createdAt"), it.number("updatedAt")) },
            data.rows("template_exercises") { TemplateExerciseEntity(it.text("id"), it.text("templateId"), it.text("exerciseId"),
                it.integer("sortOrder"), it.integer("targetSetCount"), it.integer("targetRepMin"), it.integer("targetRepMax"),
                it.number("defaultWeightGrams"), it.integer("restSeconds"),
                if(version>=5) it.flag("useExerciseWeight") else it.number("defaultWeightGrams") == 0L) },
            data.rows("workout_sessions") { SessionEntity(it.text("id"), it.number("startedAtUtc"), it.text("localWorkoutDate"),
                it.text("status"), it.optionalLong("endedAtUtc"), it.optionalText("feedback"), it.optionalInt("unfinishedSlot"),
                it.optionalText("templateId"), it.optionalText("templateNameSnapshot"), it.optionalLong("pausedAtUtc"),
                it.number("pausedDurationMillis"), it.optionalLong("restEndsAtUtc"),
                if(version>=6) it.optionalText("dailyPlanDate") else null) },
            data.rows("session_exercises") { SessionExerciseEntity(it.text("id"), it.text("sessionId"), it.text("exerciseId"),
                it.integer("sortOrder"), it.text("exerciseNameSnapshot"), it.integer("targetSetCountSnapshot"),
                it.integer("targetRepMinSnapshot"), it.integer("targetRepMaxSnapshot"), it.optionalInt("restSecondsSnapshot"),
                if(version>=4) it.text("recordMetricSnapshot") else "REPETITIONS",
                if(version>=6) it.flag("removedFromPlan") else false) },
            data.rows("set_records") { SetEntity(it.text("id"), it.text("sessionExerciseId"), it.integer("setIndex"), it.number("weightGrams"),
                it.integer("reps"), it.text("status"), it.optionalLong("completedAtUtc"), it.text("setType")) },
            if (version >= 2) data.rows("training_plans") { TrainingPlanEntity(it.text("id"), it.text("startDate"), it.integer("cycleDays"), it.flag("repeats")) } else emptyList(),
            if (version >= 2) data.rows("plan_days") { PlanDayEntity(it.text("planId"), it.integer("dayIndex"), it.optionalText("templateId")) } else emptyList(),
        )
        incoming.validateValues()
        val result = database.withTransaction {
            val existing = Bundle(dao.allExercises(), dao.allMuscles(), dao.allTemplates(), dao.allTemplateExercises(),
                dao.allSessions(), dao.allSessionExercises(), dao.allSets(), dao.allPlans(), dao.allPlanDays())
            val localExercises = existing.exercises.associateBy { it.id }
            val newExercises = incoming.exercises.filter { local ->
                val old = localExercises[local.id]
                if (old == null) true else {
                    require(old.isCustom == local.isCustom) { "同 ID 动作类型冲突" }
                    if (old.isCustom) {
                        require(old.copy(isFavorite = local.isFavorite, isArchived = local.isArchived, defaultWeightGrams = local.defaultWeightGrams, defaultWeightRecordGrams = local.defaultWeightRecordGrams) == local) { "同 ID 自定义动作冲突" }
                        require(existing.muscles.filter { it.exerciseId == old.id }.toSet() ==
                            incoming.muscles.filter { it.exerciseId == old.id }.toSet()) { "同 ID 动作肌群冲突" }
                    }
                    false
                }
            }
            val newIds = newExercises.map { it.id }.toSet()
            val muscles = incoming.muscles.filter { it.exerciseId in newIds }
            val templates = fresh(incoming.templates, existing.templates) { it.id }
            val templateExercises = fresh(incoming.templateExercises, existing.templateExercises) { it.id }
            val sessions = fresh(incoming.sessions, existing.sessions) { it.id }
            val sessionExercises = fresh(incoming.sessionExercises, existing.sessionExercises) { it.id }
            val sets = fresh(incoming.sets, existing.sets) { it.id }
            val plans = fresh(incoming.plans, existing.plans) { it.id }
            val planDays = fresh(incoming.planDays, existing.planDays) { "${it.planId}:${it.dayIndex}" }
            Bundle(existing.exercises + newExercises, existing.muscles + muscles, existing.templates + templates,
                existing.templateExercises + templateExercises, existing.sessions + sessions,
                existing.sessionExercises + sessionExercises, existing.sets + sets,
                existing.plans + plans, existing.planDays + planDays).validateRelations()

            val importedDrafts=fresh(drafts,dao.allDrafts()) {it.id}
            val exerciseIds=(existing.exercises+newExercises).map {it.id}.toSet()
            require(prepared.orEmpty().all {it in exerciseIds}) {"待开始动作不存在"}
            importedDrafts.forEach {row->
                require(row.templateId==null || (existing.templates+templates).any {it.id==row.templateId}) {"草稿模板不存在"}
                require(row.toDraft().exercises.all {it.exerciseId in exerciseIds}) {"草稿动作不存在"}
                val metrics=(existing.exercises+newExercises).associate {it.id to it.recordMetric}
                require(row.toDraft().exercises.all {metrics[it.exerciseId]!="SECONDS" || it.defaultWeightGrams==0L}) {"时长草稿动作不能包含重量"}
            }
            dao.importExercises(newExercises)
            dao.importMuscles(muscles)
            incoming.exercises.forEach { item ->
                localExercises[item.id]?.let { old -> dao.updateExercise(old.copy(isFavorite = item.isFavorite, isArchived = item.isArchived,
                    defaultWeightGrams = if(version>=5) item.defaultWeightGrams else old.defaultWeightGrams,
                    defaultWeightRecordGrams = if(version>=5) item.defaultWeightRecordGrams else old.defaultWeightRecordGrams)) }
            }
            dao.importTemplates(templates)
            dao.insertTemplateExercises(templateExercises)
            dao.importSessions(sessions)
            dao.importSessionExercises(sessionExercises)
            database.workoutDao().insertSets(sets)
            dao.importPlans(plans)
            dao.insertPlanDays(planDays)
            importedDrafts.forEach {dao.putTemplateDraft(it)}
            ImportResult(sessions.size, templates.size, newExercises.size)
        }
        if(applyPreferences) {
            restoredPreferences?.let {preferences?.save(it)}
            prepared?.let {preparation?.savePreparedExercises(it)}
        }
        return result
    }

    private fun <T> fresh(incoming: List<T>, existing: List<T>, key: (T) -> String): List<T> {
        val old = existing.associateBy(key)
        return incoming.filter { value ->
            val match = old[key(value)]
            require(match == null || match == value) { "备份存在同 ID 的不同记录，已取消整批导入" }
            match == null
        }
    }

    private data class Bundle(
        val exercises: List<ExerciseEntity>, val muscles: List<ExerciseMuscleEntity>,
        val templates: List<TemplateEntity>, val templateExercises: List<TemplateExerciseEntity>,
        val sessions: List<SessionEntity>, val sessionExercises: List<SessionExerciseEntity>, val sets: List<SetEntity>,
        val plans: List<TrainingPlanEntity> = emptyList(), val planDays: List<PlanDayEntity> = emptyList(),
    ) {
        fun validateValues() {
            require(plans.size <= 1 && plans.all { it.id == "current" && it.cycleDays in 1..14 })
            plans.forEach { LocalDate.parse(it.startDate) }
            require(planDays.distinctBy { it.planId to it.dayIndex }.size == planDays.size)
            unique(exercises.map { it.id }); unique(templates.map { it.id }); unique(templateExercises.map { it.id })
            unique(sessions.map { it.id }); unique(sessionExercises.map { it.id }); unique(sets.map { it.id })
            require(muscles.distinct().size == muscles.size) { "重复的动作肌群" }
            exercises.forEach {
                require(it.nameZh.isNotBlank())
                EquipmentType.valueOf(it.equipmentType)
                RecordMetric.valueOf(it.recordMetric)
                require(it.defaultWeightRecordGrams >= 0)
                it.defaultWeightGrams?.let { weight -> WorkoutRules.draft(weight, 0) }
                require(it.recordMetric != "SECONDS" || it.defaultWeightGrams == null) {"时长动作不能有默认重量"}
                require(it.estimatedSecondsPerSet == null || it.estimatedSecondsPerSet > 0)
                require(it.mediaAssetPath == null || (it.mediaAssetPath.startsWith("assets/") && ".." !in it.mediaAssetPath))
            }
            muscles.forEach { Muscle.valueOf(it.muscle); require(it.role in listOf("PRIMARY", "SECONDARY")) }
            templates.forEach {
                require(it.name.isNotBlank() && it.createdAt >= 0 && it.updatedAt >= it.createdAt)
                SplitType.valueOf(it.splitType)
            }
            templateExercises.forEach {
                require(it.sortOrder >= 0); WorkoutRules.setCount(it.targetSetCount)
                WorkoutRules.repRange(it.targetRepMin, it.targetRepMax)
                WorkoutRules.draft(it.defaultWeightGrams, 0); WorkoutRules.rest(it.restSeconds)
            }
            require(sessions.mapNotNull { it.dailyPlanDate }.distinct().size == sessions.count { it.dailyPlanDate != null }) {"同一天存在多个今日计划"}
            sessions.forEach {
                val status = SessionStatus.valueOf(it.status)
                LocalDate.parse(it.localWorkoutDate)
                require(it.startedAtUtc >= 0 && it.pausedDurationMillis >= 0)
                val unfinished = status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)
                require((it.unfinishedSlot == 1) == unfinished && (it.unfinishedSlot == null || it.unfinishedSlot == 1))
                require(if (unfinished || status == SessionStatus.READY) it.endedAtUtc == null else it.endedAtUtc != null && it.endedAtUtc >= it.startedAtUtc)
                require(it.dailyPlanDate == null || it.dailyPlanDate == it.localWorkoutDate)
                require(status != SessionStatus.READY || (it.dailyPlanDate != null && it.pausedDurationMillis == 0L && it.restEndsAtUtc == null))
                require((status == SessionStatus.PAUSED) == (it.pausedAtUtc != null))
                require(it.pausedAtUtc == null || it.pausedAtUtc >= it.startedAtUtc)
                require(it.restEndsAtUtc == null || unfinished && it.restEndsAtUtc >= it.startedAtUtc)
            }
            sessionExercises.forEach {
                require(it.sortOrder >= 0 && it.exerciseNameSnapshot.isNotBlank())
                RecordMetric.valueOf(it.recordMetricSnapshot)
                WorkoutRules.setCount(it.targetSetCountSnapshot)
                WorkoutRules.repRange(it.targetRepMinSnapshot, it.targetRepMaxSnapshot)
                it.restSecondsSnapshot?.let(WorkoutRules::rest)
            }
            sets.forEach {
                require(it.setIndex > 0); WorkoutRules.draft(it.weightGrams, it.reps)
                SetType.valueOf(it.setType)
                if (SetStatus.valueOf(it.status) == SetStatus.COMPLETED) {
                    WorkoutRules.completion(it.weightGrams, it.reps)
                    require(it.completedAtUtc != null && it.completedAtUtc >= 0)
                } else require(it.completedAtUtc == null)
            }
        }

        fun validateRelations() {
            require(planDays.all { day -> plans.any { it.id == day.planId && day.dayIndex in 0 until it.cycleDays } &&
                (day.templateId == null || templates.any { it.id == day.templateId }) })
            plans.forEach { plan -> require(planDays.filter { it.planId == plan.id }.map { it.dayIndex }.sorted() == (0 until plan.cycleDays).toList()) }
            val metricByExercise=exercises.associate {it.id to it.recordMetric}
            require(templateExercises.all {metricByExercise[it.exerciseId]!="SECONDS" || it.defaultWeightGrams==0L}) {"时长模板动作不能包含重量"}
            val metricBySession=sessionExercises.associate {it.id to it.recordMetricSnapshot}
            require(sets.all {metricBySession[it.sessionExerciseId]!="SECONDS" || it.weightGrams==0L}) {"时长训练不能包含重量"}
            val exerciseIds = exercises.map { it.id }.toSet()
            val templateIds = templates.map { it.id }.toSet()
            val sessionIds = sessions.map { it.id }.toSet()
            val sessionExerciseIds = sessionExercises.map { it.id }.toSet()
            require(muscles.all { it.exerciseId in exerciseIds })
            require(exercises.all { exercise -> muscles.any { it.exerciseId == exercise.id && it.role == "PRIMARY" } })
            require(templateExercises.all { it.templateId in templateIds && it.exerciseId in exerciseIds })
            require(sessions.all { it.templateId == null || it.templateId in templateIds })
            require(sessions.count { it.unfinishedSlot == 1 } <= 1) { "本机已有未结束训练，请先结束后再导入另一场进行中的训练" }
            require(sessionExercises.all { it.sessionId in sessionIds && it.exerciseId in exerciseIds })
            require(sets.all { it.sessionExerciseId in sessionExerciseIds })
            require(templateExercises.distinctBy { it.templateId to it.sortOrder }.size == templateExercises.size)
            require(sessionExercises.distinctBy { it.sessionId to it.sortOrder }.size == sessionExercises.size)
            require(sets.distinctBy { it.sessionExerciseId to it.setIndex }.size == sets.size)
            val byTemplate = templateExercises.groupBy { it.templateId }
            require(templates.all { byTemplate[it.id].orEmpty().size in 1..30 })
            require(sessions.mapNotNull { it.dailyPlanDate }.distinct().size == sessions.count { it.dailyPlanDate != null }) {"今日计划日期冲突"}
            val ready = sessions.filter { it.status == "READY" }.map { it.id }.toSet()
            val readyExercises = sessionExercises.filter { it.sessionId in ready }.map { it.id }.toSet()
            require(sets.none { it.sessionExerciseId in readyExercises && it.status == "COMPLETED" }) {"未开始计划不能包含完成组"}
            val removed = sessionExercises.filter { it.removedFromPlan }.map { it.id }.toSet()
            require(sets.none { it.sessionExerciseId in removed && it.status != "COMPLETED" }) {"已移除动作不能包含未完成目标"}
            val byExercise = sets.groupBy { it.sessionExerciseId }
            sessionExercises.forEach { exercise ->
                val indices = byExercise[exercise.id].orEmpty().map { it.setIndex }.sorted()
                require(indices == (1..exercise.targetSetCountSnapshot).toList()) { "组记录不完整" }
            }
        }

        private fun unique(ids: List<String>) {
            require(ids.all { it.isNotBlank() && it.length <= 128 } && ids.distinct().size == ids.size) { "备份 ID 无效或重复" }
        }
    }
}

private fun JSONObject.number(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long) { "备份整数格式错误：$key" }
    return (value as Number).toLong()
}
private fun JSONObject.integer(key: String): Int {
    val value = number(key)
    require(value in Int.MIN_VALUE..Int.MAX_VALUE)
    return value.toInt()
}
private fun JSONObject.flag(key: String): Boolean {
    val value = integer(key); require(value in 0..1)
    return value == 1
}
private fun JSONObject.text(key: String): String = (get(key) as? String) ?: error("备份文本格式错误")
private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else text(key)
private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else number(key)
private fun JSONObject.optionalInt(key: String): Int? = if (isNull(key)) null else integer(key)
private fun <T> JSONObject.rows(key: String, read: (JSONObject) -> T): List<T> {
    val values = getJSONArray(key)
    require(values.length() <= 100_000) { "备份记录数量超出限制" }
    return (0 until values.length()).map { read(values.getJSONObject(it)) }
}
