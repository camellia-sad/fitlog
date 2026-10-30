package dev.lzk.fitlog.data

import androidx.room.*
import dev.lzk.fitlog.domain.*
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "template_drafts",
    foreignKeys = [ForeignKey(entity = TemplateEntity::class, parentColumns = ["id"],
        childColumns = ["templateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["templateId"], unique = true)])
data class TemplateDraftEntity(@PrimaryKey val id: String, val templateId: String?, val content: String)

internal fun templateDraftKey(templateId: String?): String = templateId?.let { "template:$it" } ?: "new"

internal fun TemplateDraft.toDraftEntity(): TemplateDraftEntity {
    val items = JSONArray()
    exercises.forEach { item ->
        items.put(JSONObject().put("id", item.id).put("exerciseId", item.exerciseId).put("sortOrder", item.sortOrder)
            .put("targetSetCount", item.targetSetCount).put("targetRepMin", item.targetRepMin)
            .put("targetRepMax", item.targetRepMax).put("defaultWeightGrams", item.defaultWeightGrams)
            .put("restSeconds", item.restSeconds))
    }
    val value = JSONObject().put("name", name).put("splitType", splitType.name).put("exercises", items)
    return TemplateDraftEntity(templateDraftKey(id), id, value.toString())
}

internal fun TemplateDraftEntity.toDraft(): TemplateDraft {
    val value = JSONObject(content)
    val items = value.getJSONArray("exercises")
    return TemplateDraft(templateId, value.getString("name"), SplitType.valueOf(value.getString("splitType")),
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            TemplateExercise(item.getString("id"), item.getString("exerciseId"), item.getInt("sortOrder"),
                item.getInt("targetSetCount"), item.getInt("targetRepMin"), item.getInt("targetRepMax"),
                item.getLong("defaultWeightGrams"), item.getInt("restSeconds"))
        })
}
