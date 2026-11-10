package dev.lzk.fitlog.domain

/** Publishing this draft is a separate, explicit action from enabling history sharing. */
fun Workout.socialPostText(feeling:String=session.feedback.orEmpty(),unit:WeightUnit=WeightUnit.KG):String {
    require(session.status==SessionStatus.FINISHED) {"训练结束后才能分享"}
    val intro=session.localWorkoutDate.toString()+" · "+session.trainingLabel+" · "+historyStatus()+"\n"+
        (if(completedSetCount==0) "未完成训练组" else "完成 "+completedExerciseCount+" 个动作、"+completedSetCount+" 组")+" · "+
        elapsedDuration(session.endedAtUtc ?: session.startedAtUtc).toMinutes()+" 分钟"
    val reflection=feeling.trim().take(2000).takeIf{it.isNotBlank()}?.let{"\n训练感想："+it}.orEmpty()
    val content=buildString {
        append(intro);append(reflection)
        exercises.forEach {exercise->
            val completed=exercise.sets.filter{it.status==SetStatus.COMPLETED}
            if(completed.isNotEmpty()) {
                val line="\n"+exercise.exerciseNameSnapshot+"："+completed.joinToString(" / "){
                    it.displayValue(exercise.recordMetricSnapshot,unit)
                }
                if(length+line.length<=4000)append(line)
            }
        }
    }
    return content.take(4000).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1) else it}
}

/**
 * The training card a shared workout carries, so friends see it as a card rather than text. Only completed sets are
 * included; [unit] is the publisher's, so weights read the way they were entered. [caption] is what the publisher
 * wrote above the card.
 */
data class PostWorkout(
    val label:String, val startedAt:java.time.Instant, val minutes:Int, val completedSets:Int, val totalSets:Int,
    val volumeGrams:Long, val unit:WeightUnit, val exercises:List<PostWorkoutExercise>, val feeling:String="", val caption:String="",
) {
    val finished get()=completedSets>=totalSets && totalSets>0
    /** The template's short name is not shared; the card stamps the label's first character. */
    val stamp get()=label.trim().take(1).ifEmpty{"练"}
}
/** [previousBest] is set when this exercise beat its earlier heaviest set (grams), which the card marks as a record. */
data class PostWorkoutExercise(val name:String, val muscle:Muscle?, val metric:RecordMetric, val sets:List<PostSet>, val previousBest:Long?=null) {
    val best get()=sets.maxWithOrNull(compareBy<PostSet>({it.weightGrams},{it.reps}))
}
data class PostSet(val weightGrams:Long, val reps:Int)

object PostWorkouts {
    const val MAX_EXERCISES=30
    const val MAX_SETS=20
    /** Rows shown before the card folds. */
    const val VISIBLE=5
}

/** [records] maps a completed set id to the previous best weight it beat, as the workout summary computes them. */
fun Workout.postWorkout(catalog:List<Exercise>,records:Map<String,Long>,unit:WeightUnit,caption:String="",feeling:String=session.feedback.orEmpty()):PostWorkout {
    require(session.status==SessionStatus.FINISHED) {"训练结束后才能分享"}
    val rows=exercises.sortedBy{it.sortOrder}.mapNotNull {exercise->
        val done=exercise.sets.filter{it.status==SetStatus.COMPLETED}.sortedBy{it.setIndex}
        if(done.isEmpty()) return@mapNotNull null
        PostWorkoutExercise(exercise.exerciseNameSnapshot,catalog.find{it.id==exercise.exerciseId}?.primaryMuscles?.firstOrNull(),
            exercise.recordMetricSnapshot,done.take(PostWorkouts.MAX_SETS).map{PostSet(it.weightGrams,it.reps)},
            done.firstNotNullOfOrNull{records[it.id]})
    }.take(PostWorkouts.MAX_EXERCISES)
    val volume=exercises.filter{it.recordMetricSnapshot==RecordMetric.REPETITIONS}.flatMap{it.sets}.filter{it.status==SetStatus.COMPLETED}
        .fold(0L){total,set->(total+set.weightGrams*set.reps).coerceAtMost(Long.MAX_VALUE/2)}
    return PostWorkout(session.trainingLabel,session.startedAtUtc,elapsedDuration(session.endedAtUtc ?: session.startedAtUtc).toMinutes().toInt(),
        completedSetCount,exercises.sumOf{it.sets.size},volume,unit,rows,feeling.trim().take(1000),caption.trim().take(1000))
}
