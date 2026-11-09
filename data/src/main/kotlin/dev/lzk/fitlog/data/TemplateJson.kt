package dev.lzk.fitlog.data

import dev.lzk.fitlog.domain.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Clipboard form of a template: a small JSON object marked with [TemplateTransfer.FORMAT] and a version. Weights are
 * whole grams so no unit conversion is involved. Parsing tolerates text around the object (chat apps may add some)
 * and reports problems in one short sentence.
 */
object TemplateJson {
    fun encode(template: SharedTemplate): String = JSONObject()
        .put("format", TemplateTransfer.FORMAT).put("version", TemplateTransfer.VERSION)
        .put("name", template.name).apply { template.shortName?.let { put("shortName", it) } }
        .put("split", template.splitType.name)
        .put("exercises", JSONArray(template.exercises.map { item ->
            JSONObject().put("id", item.exerciseId).put("name", item.name).apply { if (item.nameEn.isNotBlank()) put("nameEn", item.nameEn) }
                .put("metric", item.recordMetric.name).put("sets", item.sets).put("repMin", item.repMin).put("repMax", item.repMax)
                .put("rest", item.restSeconds).put("weight", if (item.useExerciseWeight) "follow" else "fixed")
                .apply { if (!item.useExerciseWeight) put("weightGrams", item.weightGrams) }
                .apply { item.custom?.let { put("custom", JSONObject().put("equipment", it.equipment.name)
                    .put("primary", JSONArray(it.primaryMuscles.map(Muscle::name))).put("secondary", JSONArray(it.secondaryMuscles.map(Muscle::name)))) } }
        })).toString()

    /** Cheap check before parsing: does the text look like one of our templates at all? */
    fun looksLikeTemplate(text: String) = text.contains("\"${TemplateTransfer.FORMAT}\"")

    fun decode(text: String): SharedTemplate {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        fail(start >= 0 && end > start && looksLikeTemplate(text), "剪贴板里不是 FitLog 模板")
        val root = try { JSONObject(text.substring(start, end + 1)) } catch (_: JSONException) { invalid("模板内容不完整，请重新复制") }
        fail(root.optString("format") == TemplateTransfer.FORMAT, "剪贴板里不是 FitLog 模板")
        val version = root.optInt("version", -1)
        fail(version >= 1, "模板格式无效")
        fail(version <= TemplateTransfer.VERSION, "模板来自更新版本的 FitLog，请先升级")
        val list = root.optJSONArray("exercises") ?: invalid("模板缺少动作列表")
        val exercises = (0 until list.length()).map { index ->
            val item = list.optJSONObject(index) ?: invalid("第 ${index + 1} 个动作格式无效")
            try {
                val follow = when (item.optString("weight", "follow")) { "follow" -> true; "fixed" -> false; else -> invalid("重量方式无效") }
                SharedTemplateExercise(item.text("id"), item.text("name"), item.optString("nameEn"),
                    enumOr(item.optString("metric", RecordMetric.REPETITIONS.name), "记录单位无效"),
                    item.int("sets"), item.int("repMin"), item.int("repMax"), item.int("rest"), follow,
                    if (follow) 0 else item.long("weightGrams"),
                    item.optJSONObject("custom")?.let { custom ->
                        SharedExerciseDefinition(enumOr(custom.optString("equipment"), "器械类型无效"),
                            muscles(custom.optJSONArray("primary")), muscles(custom.optJSONArray("secondary")))
                    })
            } catch (e: WorkoutException) { invalid("第 ${index + 1} 个动作：${e.message}") }
        }
        return SharedTemplate(root.text("name"), root.optString("shortName").takeIf { it.isNotBlank() },
            enumOr(root.optString("split", SplitType.FREE.name), "分化类型无效"), exercises).also(TemplateTransfer::validate)
    }

    private fun JSONObject.text(key: String): String = (opt(key) as? String)?.takeIf { it.isNotBlank() } ?: invalid("缺少字段 $key")
    private fun JSONObject.int(key: String): Int = (opt(key) as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt() ?: invalid("字段 $key 须为整数")
    private fun JSONObject.long(key: String): Long = (opt(key) as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong() ?: invalid("字段 $key 须为整数")
    private fun muscles(array: JSONArray?): Set<Muscle> =
        (0 until (array?.length() ?: 0)).map { enumOr<Muscle>(array!!.optString(it), "部位无效") }.toSet()
    private inline fun <reified T : Enum<T>> enumOr(value: String, message: String): T =
        enumValues<T>().find { it.name == value } ?: invalid(message)
    private fun fail(condition: Boolean, message: String) { if (!condition) invalid(message) }
    private fun invalid(message: String): Nothing = throw WorkoutException(WorkoutError.INVALID_INPUT, message)
}
