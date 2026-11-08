package dev.lzk.fitlog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.HistoryViewModel
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun HistoryScreen(model: HistoryViewModel, unit: WeightUnit, exercises: List<Exercise>, onBack: () -> Unit, onShare: ((Workout) -> Unit)? = null,
    onPickExercise: () -> Unit = {}) {
    val year by model.year.collectAsStateWithLifecycle()
    val all by model.history.collectAsStateWithLifecycle()
    val date by model.selectedDate.collectAsStateWithLifecycle()
    val selectedExercise by model.selectedExerciseId.collectAsStateWithLifecycle()
    val performance by model.performance.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val clearing by model.clearingDate.collectAsStateWithLifecycle()
    val clearNotice by model.clearNotice.collectAsStateWithLifecycle()
    val workouts = remember(all, year) { all.filter { it.session.localWorkoutDate.year == year } }
    val statistics = remember(workouts) { TrainingHistory.statistics(workouts) }
    val selectedDay = remember(workouts, date) { workouts.filter { it.session.localWorkoutDate == date } }
    val days = remember(workouts) { TrainingHistory.days(workouts).associateBy { it.date } }
    var pickingDate by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        FitTopBar("训练历史", onBack, backLabel = "返回主页") {
            TextButton(onClick = { pickingDate = true }, modifier = Modifier.testTag("history-pick-date")) { Text("选择日期") }
        }
        LazyColumn(Modifier.weight(1f).testTag("history-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { item { Text(it, color = scheme.error) } }
            item {
                SoftCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { model.setYear(year - 1) }, enabled = year > 1970, modifier = Modifier.semanticsLabel("上一年")) {
                            LineIcon(Symbol.BACK, Modifier.size(16.dp), if (year > 1970) scheme.primary else scheme.outline)
                        }
                        Text("$year", Modifier.weight(1f), style = pixelNumber(22.sp, FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        val canNext = year < LocalDate.now().year
                        IconButton(onClick = { model.setYear(year + 1) }, enabled = canNext, modifier = Modifier.semanticsLabel("下一年")) {
                            LineIcon(Symbol.ARROW, Modifier.size(16.dp), if (canNext) scheme.primary else scheme.outline)
                        }
                    }
                    TrainingCalendar(year, days, date, model::selectDate)
                    TrainingColorLegend()
                }
            }
            item { YearFigures(statistics, unit) }
            item(key = "selected-day") {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${date.monthValue.toString().padStart(2, '0')}·${date.dayOfMonth.toString().padStart(2, '0')}", style = pixelNumber(44.sp),
                        color = scheme.onSurface, modifier = Modifier.testTag("history-date").semantics { contentDescription = date.toString() })
                    Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE), Modifier.weight(1f).padding(bottom = 6.dp),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
                    ClearTrainingDayButton(date, selectedDay.size,
                        selectedDay.any { it.session.status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) },
                        clearing) { target -> openId = null; model.clearDate(target) }
                }
                clearNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.primary) }
            }
            if (selectedDay.isEmpty()) item {
                val outline = scheme.outline
                Row(Modifier.fillMaxWidth().drawBehind {
                    drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("这一天还没有训练记录。", Modifier.weight(1f), color = scheme.onSurfaceVariant)
                    PixelPuppy(Modifier.size(44.dp), PuppyPose.SLEEP)
                }
            }
            items(selectedDay, key = { it.session.id }) { workout ->
                SessionCard(workout, exercises, unit, open = openId == workout.session.id, onShare = onShare) {
                    openId = if (openId == workout.session.id) null else workout.session.id
                }
            }
            item(key = "trend") {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(scheme.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("动作趋势", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                    val chosen = selectedExercise?.let { id -> exercises.find { it.id == id } }
                    val chosenName = chosen?.nameZh ?: selectedExercise?.let { id ->
                        workouts.flatMap { it.exercises }.find { it.exerciseId == id }?.exerciseNameSnapshot
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onPickExercise).testTag("history-trend-pick")
                        .semantics(mergeDescendants = true) { contentDescription = chosenName?.let { "动作趋势：$it，更换" } ?: "选择一个动作" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (selectedExercise != null) ExerciseThumb(selectedExercise!!, chosenName.orEmpty(), chosen?.primaryMuscles?.firstOrNull()?.group,
                            chosen?.equipmentType, Modifier.size(40.dp), corner = 9.dp)
                        Text(chosenName ?: "选择一个动作", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = if (chosenName == null) scheme.primary else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LineIcon(Symbol.ARROW, Modifier.size(11.dp), scheme.onSurfaceVariant)
                    }
                    if (selectedExercise != null) {
                        val points = remember(performance, selectedExercise) { TrainingHistory.performance(performance, selectedExercise!!) }
                        key(selectedExercise) { PerformanceChart(points, unit, chosen?.recordMetric ?: points.lastOrNull()?.recordMetric ?: RecordMetric.REPETITIONS) }
                    }
                }
            }
        }
    }
    if (pickingDate) DatePickerSheet("选择日期", date, onDismiss = { pickingDate = false }, latest = LocalDate.now(),
        sets = remember(all) { TrainingHistory.days(all).associate { it.date to it.completedSets } }) {
        model.selectDate(it); openId = null; pickingDate = false
    }
}

/** Days, sessions, sets and time in one ruled row, the training volume under them. */
@Composable
private fun YearFigures(statistics: TrainingStatistics, unit: WeightUnit) {
    val scheme = MaterialTheme.colorScheme
    val volume = BigDecimal(statistics.volumeGrams)
        .divide(if (unit == WeightUnit.KG) BigDecimal("1000") else BigDecimal("453.59237"), 2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    val hours = statistics.durationSeconds / 3600
    val minutes = (statistics.durationSeconds % 3600) / 60
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape).testTag("history-year-figures")) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            listOf(statistics.days.toString() to "天", statistics.sessions.toString() to "次", statistics.completedSets.toString() to "组",
                (if (hours > 0) "${hours}h" else "${minutes}m") to "时长").forEachIndexed { index, (value, label) ->
                if (index > 0) VerticalDivider(color = scheme.outlineVariant)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(value, style = pixelNumber(24.sp), color = scheme.onSurface, maxLines = 1, softWrap = false)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("训练量", Modifier.weight(1f).padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text(volume, style = pixelNumber(20.sp), color = scheme.onSurface)
            Text("${unit.label}·次", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** One session: start time, title and status, three figures and the muscle mix; opens in place to every set. */
@Composable
private fun SessionCard(workout: Workout, catalog: List<Exercise>, unit: WeightUnit, open: Boolean, onShare: ((Workout) -> Unit)?, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(150, easing = StepEasing(3)), label = "详情")
    val mix = remember(workout, catalog) { workout.composition(catalog) }
    val status = workout.historyStatus()
    Column(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape).testTag("history-session")) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.width(46.dp)) {
                Text(workout.session.startedAtUtc.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), style = pixelNumber(20.sp), color = scheme.onSurface)
                Text("开始", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(workout.session.trainingLabel, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val done = status == "已完成"
                    Text(status, Modifier.background(if (done) scheme.primaryContainer else scheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (done) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
                }
                if (workout.session.status == SessionStatus.READY) Text("计划尚未开始", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Figure("${workout.completedExerciseCount}", "动作")
                    Figure("${workout.completedSetCount}/${workout.plannedSetCount}", "组")
                    Figure("${workout.elapsedDuration(Instant.now()).toMinutes()}", "分钟")
                }
                if (mix.isNotEmpty()) CompositionBar(mix, Modifier.fillMaxWidth().height(6.dp))
                workout.session.feedback?.let { feeling ->
                    val line = scheme.outline
                    Text(feeling, Modifier.drawBehind { drawRect(line, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }.padding(start = 9.dp),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LineIcon(Symbol.DOWN, Modifier.align(Alignment.CenterVertically).size(11.dp).rotate(turn), scheme.onSurfaceVariant)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(200, easing = StepEasing(4))), exit = shrinkVertically(tween(150, easing = StepEasing(3)))) {
            Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant.copy(alpha = .45f)).testTag("history-session-detail")) {
                HorizontalDivider(color = scheme.outlineVariant)
                workout.exercises.forEachIndexed { index, exercise ->
                    if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                    ExerciseSets(exercise, catalog.find { it.id == exercise.exerciseId }, unit)
                }
                if (onShare != null && workout.session.status == SessionStatus.FINISHED) {
                    HorizontalDivider(color = scheme.outlineVariant)
                    Row(Modifier.padding(12.dp)) {
                        PixelOutlineKey(onClick = { onShare(workout) }, minHeight = 34.dp) {
                            LineIcon(Symbol.ARROW, Modifier.size(11.dp), scheme.onSurfaceVariant); Text("分享到动态", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = pixelNumber(22.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
        Text(label, Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One exercise of a session: a cell per set, weight × reps (or seconds); unfinished targets are dashed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseSets(exercise: SessionExercise, definition: Exercise?, unit: WeightUnit) {
    val scheme = MaterialTheme.colorScheme
    val hue = definition?.primaryMuscles?.firstOrNull()?.group?.color() ?: scheme.outline
    val missing = exercise.sets.count { it.status != SetStatus.COMPLETED }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(hue))
            Text(exercise.exerciseNameSnapshot, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (exercise.removedFromPlan) Text("已移出", Modifier.background(scheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 5.dp),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            exercise.sets.forEach { record -> SetCell(record, exercise.recordMetricSnapshot, unit) }
        }
        if (!exercise.removedFromPlan && missing > 0) Text("未完成 $missing 组", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun SetCell(record: SetRecord, metric: RecordMetric, unit: WeightUnit) {
    val scheme = MaterialTheme.colorScheme
    val done = record.status == SetStatus.COMPLETED
    val outline = scheme.outline
    Row(Modifier.height(24.dp)
        .then(if (done) Modifier.background(scheme.surface, PixelShapeSmall).border(1.dp, scheme.outlineVariant, PixelShapeSmall)
            else Modifier.drawBehind { drawRect(outline, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))) })
        .padding(horizontal = 6.dp).semantics(mergeDescendants = true) {
            contentDescription = "第${record.setIndex}组 " + if (done) record.displayValue(metric, unit) else "未完成"
        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        if (!done) Text("—", style = pixelNumber(15.sp), color = scheme.onSurfaceVariant)
        else if (metric == RecordMetric.SECONDS) {
            Text("${record.reps}", style = pixelNumber(15.sp), color = scheme.onSurface); Text("秒", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        } else {
            Text(WeightUnits.format(record.weightGrams, unit), style = pixelNumber(15.sp), color = scheme.onSurface)
            Text("×", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Text("${record.reps}", style = pixelNumber(15.sp), color = scheme.onSurface)
        }
    }
}

/** Share of completed (or, before any, planned) sets per muscle group. */
private fun Workout.composition(catalog: List<Exercise>): List<GroupShare> {
    val sets = LinkedHashMap<MuscleGroup, Int>()
    val counted = if (completedSetCount > 0) { e: SessionExercise -> e.sets.count { it.status == SetStatus.COMPLETED } } else { e: SessionExercise -> e.sets.size }
    exercises.forEach { e -> catalog.find { it.id == e.exerciseId }?.primaryMuscles?.firstOrNull()?.group?.let { sets[it] = (sets[it] ?: 0) + counted(e) } }
    val total = sets.values.sum().takeIf { it > 0 } ?: return emptyList()
    return sets.entries.sortedByDescending { it.value }.map { GroupShare(it.key, it.value / total.toFloat()) }
}

@Composable
private fun TrainingCalendar(year: Int, days: Map<LocalDate, TrainingDay>, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val first = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(first, LocalDate.of(year, 12, 31)) / 7) + 1).toInt()
    val scroll = rememberScrollState()
    val weekWidth = with(LocalDensity.current) { 30.dp.toPx() }
    LaunchedEffect(year, selected.monthValue) {
        snapshotFlow { scroll.maxValue }.first { it > 0 }
        val target = java.time.temporal.ChronoUnit.DAYS.between(first, selected.withDayOfMonth(1)).coerceAtLeast(0) / 7
        scroll.scrollTo((target * weekWidth).toInt().coerceAtMost(scroll.maxValue))
    }
    Row(Modifier.testTag("history-calendar").horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(weeks) { week ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val monday = first.plusWeeks(week.toLong())
                Text(if (week == 0) "1月" else if (monday.dayOfMonth <= 7) "${monday.monthValue}月" else " ", style = MaterialTheme.typography.labelSmall)
                repeat(7) { day ->
                    val date = monday.plusDays(day.toLong())
                    val count = days[date]?.completedSets ?: 0
                    Box(Modifier.size(26.dp)
                        .background(trainingColor(count).copy(alpha = if (date.year == year) 1f else .15f), RoundedCornerShape(2.dp))
                        .border(if (date == selected) 2.dp else 0.dp, if (date == selected) fitColors.orange else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp))
                        .clickable(enabled = date.year == year) { onSelect(date) }.semanticsLabel("$date：已结束训练完成 $count 组"))
                }
            }
        }
    }
}

@Composable
internal fun trainingColor(count: Int): androidx.compose.ui.graphics.Color {
    val primary = MaterialTheme.colorScheme.primary
    return when {
        count == 0 -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)
        count <= 3 -> primary.copy(alpha = .3f)
        count <= 8 -> primary.copy(alpha = .55f)
        count <= 15 -> primary.copy(alpha = .78f)
        else -> primary
    }
}

@Composable
private fun TrainingColorLegend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0 to "0", 1 to "1～3", 4 to "4～8", 9 to "9～15", 16 to "16+").forEach { (count, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(16.dp).background(trainingColor(count), RoundedCornerShape(2.dp)).semanticsLabel("完成 $label 组的颜色"))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


@Composable
fun WorkoutDetails(workout: Workout, unit: WeightUnit) {
    Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${workout.session.localWorkoutDate} · ${workout.session.trainingLabel} · ${workout.historyStatus()}")
        Text("${workout.completedExerciseCount} 个动作 · ${workout.completedSetCount} 组 · ${workout.elapsedDuration(Instant.now()).display()}",
            color = MaterialTheme.colorScheme.primary)
        workout.exercises.forEach { exercise ->
            Text(exercise.exerciseNameSnapshot + if(exercise.removedFromPlan) " · 已移出计划，保留完成记录" else "", style = MaterialTheme.typography.titleSmall)
            if(!exercise.removedFromPlan && exercise.sets.any { it.status != SetStatus.COMPLETED }) Text("未完成目标：" + exercise.sets.count { it.status != SetStatus.COMPLETED } + " 组", color = MaterialTheme.colorScheme.primary)
            exercise.sets.forEach { record ->
                Text("${record.setIndex}.  ${record.displayValue(exercise.recordMetricSnapshot,unit)} · ${record.status.label()}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        workout.session.feedback?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
fun SessionStatus.label(): String = when (this) {
    SessionStatus.READY -> "未开始"; SessionStatus.ACTIVE -> "进行中"; SessionStatus.PAUSED -> "已暂停"; SessionStatus.FINISHED -> "已结束"; SessionStatus.DISCARDED -> "已放弃"
}
private fun SetStatus.label(): String = when (this) { SetStatus.PLANNED -> "草稿"; SetStatus.COMPLETED -> "已完成"; SetStatus.SKIPPED -> "已跳过" }
