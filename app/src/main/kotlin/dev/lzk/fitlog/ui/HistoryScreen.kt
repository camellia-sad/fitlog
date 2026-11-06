package dev.lzk.fitlog.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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
import java.time.temporal.TemporalAdjusters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(model: HistoryViewModel, unit: WeightUnit, exercises: List<Exercise>, onBack: () -> Unit,onShare:((Workout)->Unit)?=null) {
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
    var pickingExercise by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    val names = (exercises.map { it.id to it.nameZh } +
        workouts.flatMap { it.exercises }.map { it.exerciseId to it.exerciseNameSnapshot }).distinctBy { it.first }
    Column(Modifier.fillMaxSize()) {
        FitTopBar("训练历史", onBack, backLabel = "返回主页") { TextButton(onClick = { pickingDate = true }) { Text("选择日期") } }
        LazyColumn(Modifier.weight(1f).testTag("history-list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                SoftCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { model.setYear(year - 1) }, enabled = year > 1970, modifier = Modifier.semanticsLabel("上一年")) {
                            LineIcon(Symbol.BACK, Modifier.size(16.dp), if (year > 1970) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                        Text("$year", Modifier.weight(1f), style = pixelNumber(22.sp, androidx.compose.ui.text.font.FontWeight.Bold),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        val canNext = year < LocalDate.now().year
                        IconButton(onClick = { model.setYear(year + 1) }, enabled = canNext, modifier = Modifier.semanticsLabel("下一年")) {
                            LineIcon(Symbol.ARROW, Modifier.size(16.dp), if (canNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                    }
                    TrainingCalendar(year, days, date, model::selectDate)
                    TrainingColorLegend()
                }
            }
            item {
                SoftCard {
                    Text("$year 年 · 真实完成", style = MaterialTheme.typography.titleMedium)
                    val volume = BigDecimal(statistics.volumeGrams)
                        .divide(if (unit == WeightUnit.KG) BigDecimal("1000") else BigDecimal("453.59237"), 2, java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros().toPlainString()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(statistics.days.toString(), "天", Modifier.weight(1f))
                        StatTile(statistics.sessions.toString(), "次", Modifier.weight(1f))
                        StatTile(statistics.completedSets.toString(), "组", Modifier.weight(1f))
                    }
                    Text("累计 ${Duration.ofSeconds(statistics.durationSeconds).display()} · 训练量 $volume ${unit.label}·次",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "selected-day") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OneLineText(date.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ClearTrainingDayButton(date, selectedDay.size,
                        selectedDay.any { it.session.status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) },
                        clearing) { target -> detailId = null; model.clearDate(target) }
                }
                clearNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            if (selectedDay.isEmpty()) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("这一天还没有训练记录。", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PixelPuppy(Modifier.size(48.dp), PuppyPose.SLEEP)
                }
            }
            items(selectedDay, key = { it.session.id }) { workout ->
                SoftCard(Modifier.clickable { detailId = workout.session.id }) {
                    OneLineText("${workout.session.trainingLabel} · ${workout.historyStatus()}",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if(workout.session.status == SessionStatus.READY) "计划尚未开始" else if (workout.completedSetCount == 0) "未完成 · ${workout.elapsedDuration(Instant.now()).display()}" else
                        "${workout.completedExerciseCount} 个动作 · ${workout.completedSetCount}/${workout.plannedSetCount} 组 · ${workout.elapsedDuration(Instant.now()).display()}")
                    if(workout.session.dailyPlanDate != null) {
                        val pending = workout.exercises.filter { !it.removedFromPlan && it.sets.any { set -> set.status != SetStatus.COMPLETED } }
                        if(pending.isNotEmpty()) Text("未完成：" + pending.joinToString("、") { it.exerciseNameSnapshot },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(workout.session.startedAtUtc.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        workout.session.feedback?.let { feeling -> Text(feeling, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if(onShare!=null && workout.session.status==SessionStatus.FINISHED) TextButton(onClick={onShare(workout)}){Text("分享到动态")}
                    }
                }
            }
            item {
                HorizontalDivider()
                Text("动作趋势", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                OutlinedButton(onClick = { pickingExercise = true }) { Text(names.find { it.first == selectedExercise }?.second ?: "选择一个动作") }
            }
            if (selectedExercise != null) item {
                val points = remember(performance, selectedExercise) { TrainingHistory.performance(performance, selectedExercise!!) }
                key(selectedExercise) { PerformanceChart(points, unit, exercises.find { it.id == selectedExercise }?.recordMetric ?: points.lastOrNull()?.recordMetric ?: RecordMetric.REPETITIONS) }
            }
        }
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickingDate = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { model.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                pickingDate = false
            }) { Text("选择") } }, dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("取消") } }) { DatePicker(picker) }
    }
    if (pickingExercise) AlertDialog(onDismissRequest = { pickingExercise = false }, title = { Text("查看动作历史") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(names, key = { it.first }) { (id, name) ->
                TextButton(onClick = { model.selectedExerciseId.value = id; pickingExercise = false }, modifier = Modifier.fillMaxWidth()) { Text(name) }
            }
        } }, confirmButton = { TextButton(onClick = { pickingExercise = false }) { Text("关闭") } })
    workouts.find { it.session.id == detailId }?.let { workout ->
        AlertDialog(onDismissRequest = { detailId = null }, title = { Text("训练详情") },
            text = { WorkoutDetails(workout, unit) }, confirmButton = { TextButton(onClick = { detailId = null }) { Text("关闭") } })
    }
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
private fun trainingColor(count: Int): androidx.compose.ui.graphics.Color {
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
