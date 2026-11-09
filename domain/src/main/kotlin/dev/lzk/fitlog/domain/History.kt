package dev.lzk.fitlog.domain

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

data class TrainingDay(val date: LocalDate, val completedSets: Int, val sessions: Int)
data class PerformancePoint(val sessionId: String, val date: LocalDate, val weightGrams: Long, val reps: Int,val recordMetric:RecordMetric=RecordMetric.REPETITIONS)
data class TrainingStatistics(val days: Int, val sessions: Int, val completedSets: Int, val durationSeconds: Long, val volumeGrams: BigInteger)

interface HistoryRepository {
    fun observeHistory(from: LocalDate, to: LocalDate): Flow<List<Workout>>
    fun observeExerciseHistory(exerciseId: String): Flow<List<Workout>>
}

fun Workout.historyStatus(): String = when (session.status) {
    SessionStatus.READY -> "未开始"
    SessionStatus.FINISHED -> if (allSetsCompleted) "已完成" else "未完成"
    SessionStatus.ACTIVE -> "进行中 · 未完成"
    SessionStatus.PAUSED -> "已暂停 · 未完成"
    SessionStatus.DISCARDED -> "已放弃"
}

fun Workout.isRecordedTraining() = session.status == SessionStatus.FINISHED ||
    (session.dailyPlanDate != null && session.status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED))

object TrainingHistory {
    fun statistics(workouts: List<Workout>): TrainingStatistics {
        val finished = workouts.filter { it.isRecordedTraining() && it.completedSetCount > 0 }
        val completed = finished.flatMap { it.exercises }.flatMap { it.sets }.filter { it.status == SetStatus.COMPLETED }
        val volume = finished.flatMap {it.exercises}.filter {it.recordMetricSnapshot==RecordMetric.REPETITIONS}
            .flatMap {it.sets}.filter {it.status==SetStatus.COMPLETED}.fold(BigInteger.ZERO) { value, set ->
            value + BigInteger.valueOf(set.weightGrams) * BigInteger.valueOf(set.reps.toLong())
        }
        return TrainingStatistics(
            finished.map { it.session.localWorkoutDate }.distinct().size, finished.size, completed.size,
            finished.fold(0L) { total, workout ->
                val seconds = workout.elapsedDuration(workout.session.endedAtUtc ?: Instant.now()).seconds
                if (Long.MAX_VALUE - total < seconds) Long.MAX_VALUE else total + seconds
            }, volume,
        )
    }

    fun days(workouts: List<Workout>): List<TrainingDay> = workouts
        .filter { it.isRecordedTraining() && it.completedSetCount > 0 }
        .groupBy { it.session.localWorkoutDate }.map { (date, sessions) ->
            TrainingDay(date, sessions.sumOf { it.completedSetCount }, sessions.size)
        }.sortedBy { it.date }

    /** Consecutive Monday-start weeks with a recorded session, ending this week, or last week while this one has none yet. */
    fun weekStreak(workouts: List<Workout>, today: LocalDate): Int {
        val weeks = days(workouts).map { it.date.with(java.time.DayOfWeek.MONDAY) }.toSet()
        var week = today.with(java.time.DayOfWeek.MONDAY)
        if (week !in weeks) week = week.minusWeeks(1)
        var count = 0
        while (week in weeks) { count++; week = week.minusWeeks(1) }
        return count
    }

    fun performance(workouts: List<Workout>, exerciseId: String): List<PerformancePoint> = workouts
        .filter { it.isRecordedTraining() }
        .sortedBy { it.session.startedAtUtc }.flatMap { workout ->
            workout.exercises.filter { it.exerciseId == exerciseId }.flatMap {exercise->
                exercise.sets.filter { it.status == SetStatus.COMPLETED }.map {
                    PerformancePoint(workout.session.id, workout.session.localWorkoutDate, it.weightGrams, it.reps,exercise.recordMetricSnapshot)
                }
            }
        }
}
