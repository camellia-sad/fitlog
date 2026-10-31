package dev.lzk.fitlog.domain

/** Publishing this draft is a separate, explicit action from enabling history sharing. */
fun Workout.socialPostText(feeling:String=session.feedback.orEmpty()):String {
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
                    it.displayValue(exercise.recordMetricSnapshot)
                }
                if(length+line.length<=4000)append(line)
            }
        }
    }
    return content.take(4000).let{if(it.lastOrNull()?.isHighSurrogate()==true)it.dropLast(1) else it}
}
