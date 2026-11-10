package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * End of a free or extra workout, in the same set as the plan-complete popup: puppy, title and time, three pixel
 * figures, any new records, a row per exercise (a cell per set and its heaviest set), the feeling, then
 * [分享到动态] and [完成].
 */
@Composable
fun WorkoutSummaryDialog(summary: Workout, records: Map<String, Long>, catalog: List<Exercise>, unit: WeightUnit,
    onShare: (String) -> Unit, onDone: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fit = fitColors
    var feeling by rememberSaveable(summary.session.id) { mutableStateOf(summary.session.feedback.orEmpty()) }
    val all = summary.allSetsCompleted && summary.plannedSetCount > 0
    val bests = summary.exercises.mapNotNull { e -> e.sets.firstOrNull { it.id in records }?.let { Triple(e.exerciseNameSnapshot, it.weightGrams, records.getValue(it.id)) } }
    val started = summary.session.startedAtUtc.atZone(ZoneId.systemDefault())
    AlertDialog(onDismissRequest = { onDone(feeling) }, modifier = Modifier.testTag("workout-summary"),
        icon = { PixelPuppy(Modifier.size(64.dp), if (all) PuppyPose.CELEBRATE else PuppyPose.REST) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (all) "全部目标已完成" else "本次训练已结束")
                Text(summary.session.trainingLabel + " · " + started.format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("${summary.completedExerciseCount}" to "动作", "${summary.completedSetCount}" to "组",
                        "${summary.elapsedDuration(summary.session.endedAtUtc ?: java.time.Instant.now()).toMinutes()}" to "分钟").forEach { (value, label) ->
                        Column(Modifier.weight(1f).background(scheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(value, style = pixelNumber(26.sp), color = scheme.onSurface)
                            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                if (bests.isNotEmpty()) Column(Modifier.fillMaxWidth().background(fit.cream, PixelShapeSmall).padding(10.dp).testTag("summary-records"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bests.forEach { (name, weight, before) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LineIcon(Symbol.MEDAL, Modifier.size(13.dp), fit.orangeDeep)
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
                            Text(WeightUnits.format(weight, unit), style = pixelNumber(19.sp), color = scheme.onSurface)
                            Text("${unit.label} · 之前 ${WeightUnits.format(before, unit)}", style = MaterialTheme.typography.labelSmall, color = fit.orangeDeep)
                        }
                    }
                }
                val shape = RoundedCornerShape(12.dp)
                Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, scheme.outlineVariant, shape).testTag("summary-exercises")) {
                    summary.exercises.forEachIndexed { index, exercise ->
                        if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                        SummaryExercise(exercise, catalog.find { it.id == exercise.exerciseId }, unit)
                    }
                }
                OutlinedTextField(feeling, { feeling = it.take(2000) }, label = { Text("训练感想（选填）") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        dismissButton = {
            PixelOutlineKey(onClick = { onShare(feeling) }, minHeight = 42.dp, contentColor = scheme.onSurface, modifier = Modifier.testTag("summary-share")) {
                LineIcon(Symbol.EXPORT, Modifier.size(12.dp), LocalContentColor.current); Text("分享到动态", maxLines = 1)
            }
        },
        confirmButton = {
            PixelKey(onClick = { onDone(feeling) }, minHeight = 42.dp, minWidth = 88.dp, depth = 4.dp, modifier = Modifier.testTag("summary-done")) {
                LineIcon(Symbol.CHECK, Modifier.size(12.dp), LocalContentColor.current); Text("完成", maxLines = 1)
            }
        })
}

/** One exercise: muscle-colour strip, name, a cell per set (filled when done) and the heaviest completed set. */
@Composable
private fun SummaryExercise(exercise: SessionExercise, definition: Exercise?, unit: WeightUnit) {
    val scheme = MaterialTheme.colorScheme
    val hue = definition?.primaryMuscles?.firstOrNull()?.group?.color() ?: scheme.outline
    val done = exercise.sets.filter { it.status == SetStatus.COMPLETED }
    val top = when {
        done.isEmpty() -> "—"
        exercise.recordMetricSnapshot == RecordMetric.SECONDS -> "${done.maxOf { it.reps }} 秒"
        else -> done.maxOf { it.weightGrams }.let { if (it == 0L) "自重" else WeightUnits.format(it, unit) }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(hue))
        Text(exercise.exerciseNameSnapshot, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            exercise.sets.take(10).forEach { set ->
                Box(Modifier.size(9.dp).then(if (set.status == SetStatus.COMPLETED) Modifier.background(scheme.primary) else Modifier.border(1.5.dp, scheme.outline)))
            }
        }
        Text(top, Modifier.widthIn(min = 44.dp), style = pixelNumber(16.sp), color = scheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
    }
}
