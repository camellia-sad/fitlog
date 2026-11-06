package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: WorkoutUiState, onStart: () -> Unit, onContinue: () -> Unit, onLibrary: () -> Unit,
    weekly: List<Workout> = emptyList(), templates: List<WorkoutTemplate> = emptyList(),
    onHistory: () -> Unit = {}, selectedTemplateId: String? = null, onSelectTemplate: (String?) -> Unit = {},
    prepared: List<Exercise> = emptyList(), onRemovePrepared: (String) -> Unit = {}, plan: TrainingPlan? = null, updates:dev.lzk.fitlog.updates.AppUpdates? = null,
    onReserve: () -> Unit = {}, onExtra: () -> Unit = {}, onAddDailySet: (String) -> Unit = {}, onRemoveAction: (String) -> Unit = {},
    catalog: List<Exercise> = emptyList()) {
    val workout = state.workout
    val todayWorkout = state.todayWorkout
    var viewingToday by remember { mutableStateOf(false) }
    var pickingTemplate by remember { mutableStateOf(false) }
    val selectedTemplate = templates.find { it.id == selectedTemplateId }
    val now by produceState(Instant.now(), workout?.session?.id) { while (true) { value = Instant.now(); delay(1_000) } }
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val dates = TrainingHistory.days(weekly).associateBy { it.date }
    val current = workout ?: todayWorkout
    val exerciseIds = current?.exercises?.filter { !it.removedFromPlan }?.map { it.exerciseId }
        ?: selectedTemplate?.exercises?.map { it.exerciseId }
        ?: if (selectedTemplateId == null) prepared.map { it.id } else emptyList()
    val groups = remember(exerciseIds, catalog, prepared) {
        exerciseIds.mapNotNull { id -> catalog.find { it.id == id } ?: prepared.find { it.id == id } }
            .flatMap { it.primaryMuscles }.map { it.group }.distinct().take(4)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelWordmark(height = if (largeFont) 26.dp else 30.dp)
                OneLineText(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 · E", Locale.SIMPLIFIED_CHINESE)),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PixelPuppy(Modifier.size(if (largeFont) 56.dp else 76.dp), when {
                workout?.session?.status == SessionStatus.PAUSED -> PuppyPose.SLEEP
                workout?.session?.status == SessionStatus.ACTIVE -> PuppyPose.LIFT
                (dates[LocalDate.now()]?.completedSets ?: 0) > 0 -> PuppyPose.CELEBRATE
                else -> PuppyPose.IDLE
            })
        }

        SoftCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val planIndex = plan?.dayIndex(LocalDate.now())
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("今日训练", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    if (planIndex != null && plan != null) OneLineText(" · 循环第 ${planIndex + 1} / ${plan.templateIds.size} 天",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onHistory, modifier = Modifier.testTag("open-history"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    LineIcon(Symbol.CLOCK, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    OneLineText("历史", style = MaterialTheme.typography.labelLarge)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OneLineText(workout?.session?.trainingLabel ?: todayWorkout?.session?.trainingLabel ?: selectedTemplate?.name ?: "开始今天的训练",
                    style = MaterialTheme.typography.headlineSmall)
                OneLineText(if (workout == null) (todayWorkout?.let { it.historyStatus() + " · " + it.completedSetCount + "/" + it.plannedSetCount + " 组" } ?: "选择自由训练或自己的模板") else "${if (workout.session.status == SessionStatus.PAUSED) "已暂停" else "训练中"} · ${workout.elapsedDuration(now).timerText()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (groups.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { MuscleChip(it.label, it) }
            }
            TodayStats(current, selectedTemplate, now, largeFont)
            if (workout == null && todayWorkout == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = selectedTemplateId == null, onClick = { onSelectTemplate(null) }, label = { Text("自由训练") })
                    FilterChip(selected = selectedTemplateId != null, onClick = { pickingTemplate = true },
                        label = { OneLineText(selectedTemplate?.name ?: "模板训练") },
                        trailingIcon = { LineIcon(Symbol.DOWN, Modifier.size(12.dp), MaterialTheme.colorScheme.onSurfaceVariant) })
                }
                plan?.dayIndex(LocalDate.now())?.let { index ->
                    val id = plan.templateIds[index]
                    Text("今日计划：" + (if (id == null) "休息，可按需自由训练" else templates.find { it.id == id }?.name ?: "模板已删除，请重新安排"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selectedTemplateId == null) {
                    prepared.forEach { exercise ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(exercise.nameZh, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onRemovePrepared(exercise.id) }) { Text("移除") }
                        }
                    }
                    OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        LineIcon(Symbol.PLUS, Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); Text("添加本次动作")
                    }
                }
            }
            if(todayWorkout == null && workout == null) OutlinedButton(onClick = onReserve, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("设为今日计划") }
            if(todayWorkout != null) OutlinedButton(onClick = { viewingToday = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home-today-actions")) {
                OneLineText("今日动作 · ${todayWorkout.completedSetCount}/${todayWorkout.plannedSetCount} 组", Modifier.weight(1f))
                LineIcon(Symbol.DOWN, Modifier.size(14.dp))
            }
            PixelButton(onClick = if (workout != null) onContinue else if(todayWorkout?.allSetsCompleted == true) ({ viewingToday = true }) else if(todayWorkout != null) onContinue else onStart,
                enabled = !state.loading && (selectedTemplateId == null || selectedTemplate != null || workout != null || todayWorkout != null),
                modifier = Modifier.fillMaxWidth(), minHeight = 56.dp) {
                LineIcon(if (workout == null && todayWorkout == null) Symbol.PLAY else Symbol.ARROW, Modifier.size(18.dp), LocalContentColor.current)
                Text(if(workout != null) "继续训练" else if(todayWorkout?.allSetsCompleted == true) "查看今日计划" else if(todayWorkout != null && todayWorkout.session.status != SessionStatus.READY) "继续今日计划" else "开始训练")
            }
        }

        if(todayWorkout != null && workout == null) OutlinedButton(onClick = onExtra, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start-extra-workout")) { Text("开始加练") }

        if (workout != null && workout.exercises.isNotEmpty()) {
            SoftCard {
                Row(Modifier.fillMaxWidth()) {
                    Text("本次进度", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("${workout.completedSetCount} / ${workout.plannedSetCount} 组", style = pixelNumber(16.sp), color = MaterialTheme.colorScheme.primary)
                }
                PixelProgress(workout.completedSetCount, workout.plannedSetCount, Modifier.fillMaxWidth())
                Text("已经完成 ${workout.completedExerciseCount} 个动作，按自己的节奏继续。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }

        WeekCard(dates)
        updates?.let {UpdateEntry(it)}
    }
    if(viewingToday && todayWorkout != null) TodayActionsDialog(todayWorkout, onClose = { viewingToday = false },
        onSelect = {}, onAdd = { viewingToday = false; onLibrary() }, onAddSet = onAddDailySet,
        onRemove = onRemoveAction, editable = true)
    if (pickingTemplate) AlertDialog(onDismissRequest = { pickingTemplate = false }, title = { Text("选择训练模板") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (templates.isEmpty()) Text("还没有模板，可到动作页创建自己的训练安排。")
            templates.forEach { template ->
                TextButton(onClick = { onSelectTemplate(template.id); pickingTemplate = false }, modifier = Modifier.fillMaxWidth()) { Text(template.name) }
            }
        } }, confirmButton = { TextButton(onClick = { pickingTemplate = false }) { Text("关闭") } })
}

/** Up to three figures that describe what today's card is about to start or already holds. */
@Composable
private fun TodayStats(current: Workout?, template: WorkoutTemplate?, now: Instant, largeFont: Boolean) {
    val tiles = when {
        current != null -> listOf(
            "${current.completedSetCount}/${current.plannedSetCount}" to "完成组",
            current.exercises.count { !it.removedFromPlan }.toString() to "个动作",
            (if (current.session.status == SessionStatus.READY) "--" else (current.elapsedDuration(now).seconds / 60).toString()) to "分钟",
        )
        template != null -> listOf(template.exercises.size.toString() to "个动作", template.exercises.sumOf { it.targetSetCount }.toString() to "目标组")
        else -> emptyList()
    }
    if (tiles.isEmpty()) return
    if (largeFont) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { tiles.forEach { (v, l) -> StatTile(v, l, Modifier.fillMaxWidth()) } }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { (v, l) -> StatTile(v, l, Modifier.weight(1f)) } }
}

/** Seven columns of stacked cells, one cell per four finished sets (max five); today is outlined in orange. */
@Composable
private fun WeekCard(dates: Map<LocalDate, TrainingDay>) {
    val today = LocalDate.now()
    val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    val trained = dates.values.count { it.completedSets > 0 }
    val sets = dates.values.sumOf { it.completedSets }
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (dates.isEmpty()) "本周还没有训练记录" else "本周训练 · $trained / 7 天", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (sets > 0) PixelBadge("$sets 组", symbol = Symbol.SPARK)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            repeat(7) { index ->
                val date = monday.plusDays(index.toLong())
                val count = dates[date]?.completedSets ?: 0
                val lit = if (count == 0) 0 else ceil(count / 4.0).toInt().coerceAtMost(5)
                val isToday = date == today
                Column(Modifier.weight(1f).semanticsLabel("${listOf("一","二","三","四","五","六","日")[index]}：完成 $count 组"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(5) { level ->
                            val on = 4 - level < lit
                            Box(Modifier.fillMaxWidth().height(7.dp)
                                .background(when { on -> MaterialTheme.colorScheme.primary; date.isAfter(today) -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.secondaryContainer })
                                .then(if (isToday && !on) Modifier.border(1.dp, fitColors.orange) else Modifier))
                        }
                    }
                    Text(if (isToday) "今天" else listOf("一","二","三","四","五","六","日")[index],
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                        color = if (isToday) fitColors.orangeDeep else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
