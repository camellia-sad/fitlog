package dev.lzk.fitlog.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val nameZh: String,
    val nameEn: String,
    val equipmentType: String,
    val instructions: String?,
    val estimatedSecondsPerSet: Int?,
    val mediaAssetPath: String?,
    val isFavorite: Boolean,
    val isCustom: Boolean,
    val isArchived: Boolean,
    @ColumnInfo(defaultValue = "'REPETITIONS'") val recordMetric:String = "REPETITIONS",
)

@Entity(
    tableName = "exercise_muscles",
    primaryKeys = ["exerciseId", "muscle", "role"],
    foreignKeys = [ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["muscle", "exerciseId"])],
)
data class ExerciseMuscleEntity(val exerciseId: String, val muscle: String, val role: String)

@Entity(tableName = "catalog_versions")
data class CatalogVersionEntity(@PrimaryKey val id: String, val version: Int)

@Entity(tableName = "workout_sessions",
    foreignKeys = [ForeignKey(entity = TemplateEntity::class, parentColumns = ["id"], childColumns = ["templateId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["unfinishedSlot"], unique = true), Index("localWorkoutDate"), Index("templateId")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAtUtc: Long,
    val localWorkoutDate: String,
    val status: String,
    val endedAtUtc: Long? = null,
    val feedback: String? = null,
    // SQLite unique indices permit multiple NULLs, but only one unfinished session.
    val unfinishedSlot: Int? = 1,
    val templateId: String? = null,
    val templateNameSnapshot: String? = null,
    val pausedAtUtc: Long? = null,
    @ColumnInfo(defaultValue = "0") val pausedDurationMillis: Long = 0,
    val restEndsAtUtc: Long? = null,
)

@Entity(
    tableName = "session_exercises",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ExerciseEntity::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["sessionId", "sortOrder"], unique = true), Index("exerciseId")],
)
data class SessionExerciseEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val sortOrder: Int,
    val exerciseNameSnapshot: String,
    val targetSetCountSnapshot: Int,
    val targetRepMinSnapshot: Int,
    val targetRepMaxSnapshot: Int,
    val restSecondsSnapshot: Int? = null,
    @ColumnInfo(defaultValue = "'REPETITIONS'") val recordMetricSnapshot:String = "REPETITIONS",
)

@Entity(
    tableName = "set_records",
    foreignKeys = [ForeignKey(entity = SessionExerciseEntity::class, parentColumns = ["id"], childColumns = ["sessionExerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionExerciseId", "setIndex"], unique = true)],
)
data class SetEntity(
    @PrimaryKey val id: String,
    val sessionExerciseId: String,
    val setIndex: Int,
    val weightGrams: Long,
    val reps: Int,
    val status: String = "PLANNED",
    val completedAtUtc: Long? = null,
    val setType: String = "WORKING",
)

data class ExerciseRow(
    @Embedded val exercise: ExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "exerciseId") val muscles: List<ExerciseMuscleEntity>,
)

data class SessionExerciseRow(
    @Embedded val exercise: SessionExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionExerciseId") val sets: List<SetEntity>,
)

data class WorkoutRow(
    @Embedded val session: SessionEntity,
    val isExtra: Boolean,
    @Relation(parentColumn = "id", entityColumn = "sessionId", entity = SessionExerciseEntity::class) val exercises: List<SessionExerciseRow>,
)
