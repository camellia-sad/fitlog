package dev.lzk.fitlog.data

import androidx.room.*

@Entity(tableName = "workout_templates")
data class TemplateEntity(
    @PrimaryKey val id: String, val name: String, val splitType: String, val createdAt: Long, val updatedAt: Long,
)

@Entity(
    tableName = "template_exercises",
    foreignKeys = [
        ForeignKey(entity = TemplateEntity::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["templateId", "sortOrder"], unique = true), Index("exerciseId")],
)
data class TemplateExerciseEntity(
    @PrimaryKey val id: String, val templateId: String, val exerciseId: String, val sortOrder: Int,
    val targetSetCount: Int, val targetRepMin: Int, val targetRepMax: Int,
    val defaultWeightGrams: Long, val restSeconds: Int,
)

data class TemplateRow(
    @Embedded val template: TemplateEntity,
    @Relation(parentColumn = "id", entityColumn = "templateId") val exercises: List<TemplateExerciseEntity>,
)
