package dev.lzk.fitlog.data

import dev.lzk.fitlog.domain.*
import java.time.Instant
import java.time.LocalDate

internal fun ExerciseRow.toDomain() = Exercise(
    id = exercise.id, nameZh = exercise.nameZh, nameEn = exercise.nameEn,
    equipmentType = EquipmentType.valueOf(exercise.equipmentType),
    primaryMuscles = muscles.filter { it.role == "PRIMARY" }.map { Muscle.valueOf(it.muscle) }.toSet(),
    secondaryMuscles = muscles.filter { it.role == "SECONDARY" }.map { Muscle.valueOf(it.muscle) }.toSet(),
    instructions = exercise.instructions, estimatedSecondsPerSet = exercise.estimatedSecondsPerSet,
    mediaAssetPath = exercise.mediaAssetPath, isFavorite = exercise.isFavorite,
    isCustom = exercise.isCustom, isArchived = exercise.isArchived,
)

internal fun SessionEntity.toDomain() = WorkoutSession(
    id, Instant.ofEpochMilli(startedAtUtc), LocalDate.parse(localWorkoutDate), SessionStatus.valueOf(status),
    endedAtUtc?.let(Instant::ofEpochMilli), feedback, templateId, templateNameSnapshot,
    pausedAtUtc?.let(Instant::ofEpochMilli), pausedDurationMillis, restEndsAtUtc?.let(Instant::ofEpochMilli),
)

internal fun SetEntity.toDomain() = SetRecord(
    id, sessionExerciseId, setIndex, weightGrams, reps, SetStatus.valueOf(status),
    completedAtUtc?.let(Instant::ofEpochMilli), SetType.valueOf(setType),
)

internal fun SessionExerciseRow.toDomain() = SessionExercise(
    exercise.id, exercise.sessionId, exercise.exerciseId, exercise.sortOrder,
    exercise.exerciseNameSnapshot, exercise.targetSetCountSnapshot,
    exercise.targetRepMinSnapshot, exercise.targetRepMaxSnapshot, sets.sortedBy { it.setIndex }.map { it.toDomain() }, exercise.restSecondsSnapshot,
)

internal fun WorkoutRow.toDomain() = Workout(session.toDomain().copy(isExtra = isExtra), exercises.sortedBy { it.exercise.sortOrder }.map { it.toDomain() })
