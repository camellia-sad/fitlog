package dev.lzk.fitlog.data

import androidx.room.*

@Entity(tableName = "training_plans")
data class TrainingPlanEntity(@PrimaryKey val id: String = "current", val startDate: String, val cycleDays: Int, val repeats: Boolean,
    /** Postponed dates as comma-separated ISO dates, oldest first; empty when nothing was moved. */
    @ColumnInfo(defaultValue = "") val postponedDates: String = "")

internal fun TrainingPlanEntity.postponed(): Set<java.time.LocalDate> =
    postponedDates.split(',').filter { it.isNotBlank() }.map(java.time.LocalDate::parse).toSet()
internal fun Set<java.time.LocalDate>.joinDates(): String = sorted().joinToString(",")

@Entity(tableName = "plan_days", primaryKeys = ["planId", "dayIndex"],
    foreignKeys = [
        ForeignKey(entity = TrainingPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TemplateEntity::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.SET_NULL),
    ], indices = [Index("templateId")])
data class PlanDayEntity(val planId: String = "current", val dayIndex: Int, val templateId: String?)

data class PlanRow(@Embedded val plan: TrainingPlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId") val days: List<PlanDayEntity>)
