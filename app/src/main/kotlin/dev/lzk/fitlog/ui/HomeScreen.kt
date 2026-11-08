package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
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
fun HomeScreen(state: WorkoutUiState, onOpen: (String?) -> Unit, onContinue: () -> Unit, onLibrary: () -> Unit,
    weekly: List<Workout> = emptyList(), templates: List<WorkoutTemplate> = emptyList(),
    onHistory: () -> Unit = {}, plan: TrainingPlan? = null, updates:dev.lzk.fitlog.updates.AppUpdates? = null,
    onAddDailySet: (String) -> Unit = {}, onRemoveAction: (String) -> Unit = {},
    catalog: List<Exercise> = emptyList(), onTemplates: () -> Unit = {}) {
    val workout = state.workout
    val todayWorkout = state.todayWorkout
    var viewingToday by remember { mutableStateOf(false) }
    var pickingTraining by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val plannedId = plan?.templateOn(today)
    val plannedTemplate = templates.find { it.id == plannedId }
    val now by produceState(Instant.now(), workout?.session?.id) { while (true) { value = Instant.now(); delay(1_000) } }
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val dates = TrainingHistory.days(weekly).associateBy { it.date }
    val current = workout ?: todayWorkout
    // A stopped record can be picked up again; a template whose targets are all done waits for an explicit choice.
    val resumable = workout != null || (todayWorkout != null && !(todayWorkout.session.templateId != null && todayWorkout.allSetsCompleted))
    val exerciseIds = current?.exercises?.filter { !it.removedFromPlan }?.map { it.exerciseId }
        ?: plannedTemplate?.exercises?.map { it.exerciseId }.orEmpty()
    val groups = remember(exerciseIds, catalog) {
        exerciseIds.mapNotNull { id -> catalog.find { it.id == id } }
            .flatMap { it.primaryMuscles }.map { it.group }.distinct().take(4)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Tall enough that the first card starts in the sky's bridge zone and the sky stays visible above it.
        Row(Modifier.fillMaxWidth().heightIn(min = 136.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
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
            }, loop = true)
        }

        SoftCard {
            // The record training now (or used last) leads the card; otherwise today's plan, a rest note or an open question.
            val postponed = current == null && plan?.isPostponed(today) == true
            val tomorrow = plan?.templateOn(today.plusDays(1))?.let { id -> templates.find { it.id == id }?.name } ?: "休息"
            val headline: Pair<String, String?> = when {
                current != null -> current.session.trainingLabel to
                    if (workout != null) "${if (workout.session.status == SessionStatus.PAUSED) "已暂停" else "训练中"} · ${workout.elapsedDuration(now).timerText()}"
                    else current.let { it.historyStatus() + " · " + it.completedSetCount + "/" + it.plannedSetCount + " 组" }
                postponed -> "休息" to "明天：$tomorrow"
                plan?.dayIndex(today) != null && plannedId == null -> "休息日" to "明天：$tomorrow"
                plannedId != null -> (plannedTemplate?.name ?: "模板已删除") to (if (plannedTemplate == null) "请到动作页重新安排计划" else null)
                else -> "今天想练什么" to null
            }
            val planned = if (current != null) current.session.templateId != null && current.session.templateId == plannedId
                else plannedTemplate != null && !postponed
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { TodayHeadline(headline.first, headline.second, planned = planned, postponed = postponed) }
                HistoryEntry(onHistory)
            }
            if (groups.isNotEmpty() && (current != null || plannedTemplate != null)) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groups.forEach { MuscleChip(it.label, it) }
            }
            TodayStats(current, plannedTemplate.takeIf { current == null && !postponed }, now, largeFont)
            val record = current?.takeIf { it.session.dailyPlanDate == today }
            if (record != null) Column {
                TodayActionsStrip(record, viewingToday) { viewingToday = !viewingToday }
                TodayActionsDropdown(viewingToday, record, catalog, onAdd = onLibrary, onAddSet = onAddDailySet, onRemove = onRemoveAction)
            }
            if (resumable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(onClick = onContinue, enabled = !state.loading, modifier = Modifier.weight(1f).testTag("home-continue"), minHeight = 56.dp) {
                    LineIcon(Symbol.ARROW, Modifier.size(18.dp), LocalContentColor.current); OneLineText("继续训练")
                }
                PixelOutlineKey(onClick = { pickingTraining = true }, enabled = !state.loading, minHeight = 56.dp,
                    contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("home-switch")) {
                    LineIcon(Symbol.SWAP, Modifier.size(16.dp), LocalContentColor.current); Spacer(Modifier.width(6.dp)); OneLineText("换训练")
                }
            } else PixelButton(onClick = { pickingTraining = true }, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().testTag("home-start"), minHeight = 56.dp) {
                LineIcon(Symbol.PLAY, Modifier.size(18.dp), LocalContentColor.current); Text("开始训练")
            }
            if (state.dayRecords.size >= 2) DayRecords(state.dayRecords, catalog, workout?.session?.id ?: todayWorkout?.session?.id, onOpen)
        }

        if (workout != null && workout.exercises.isNotEmpty()) {
            SoftCard {
                Row(Modifier.fillMaxWidth()) {
                    Text("本次进度", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("${workout.completedSetCount} / ${workout.plannedSetCount} 组", style = pixelNumber(16.sp), color = MaterialTheme.colorScheme.primary)
                }
                PixelProgress(workout.completedSetCount, workout.plannedSetCount, Modifier.fillMaxWidth())
            }
        }

        WeekCard(dates) { date -> weekDayInfo(date, weekly, catalog, plan, templates) }
        updates?.let {UpdateEntry(it)}
    }

    if (pickingTraining) TemplatePickerSheet(templates, catalog, plannedId, state.dayRecords,
        onPick = onOpen, onManage = onTemplates, onDismiss = { pickingTraining = false })
}

/**
 * Today's records under the card, one row each: muscle-group strip, name with its state, completed sets and minutes.
 * The current one is outlined in teal; tapping a row continues it.
 */
@Composable
private fun DayRecords(records: List<Workout>, catalog: List<Exercise>, currentId: String?, onOpen: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("day-records"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = scheme.outlineVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("今日记录", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
            Text("${records.size}", style = pixelNumber(18.sp), color = scheme.onSurface)
        }
        records.forEach { record ->
            val groups = remember(record, catalog) {
                record.exercises.mapNotNull { e -> catalog.find { it.id == e.exerciseId } }.flatMap { it.primaryMuscles }.map { it.group }.distinct()
            }
            val status = record.session.status
            val live = status == SessionStatus.ACTIVE || status == SessionStatus.PAUSED
            val done = !live && record.session.templateId != null && record.plannedSetCount > 0 && record.allSetsCompleted
            val cur = record.session.id == currentId
            Surface(onClick = { onOpen(record.session.daySlot) }, modifier = Modifier.fillMaxWidth().testTag("day-record-${record.session.daySlot.orEmpty().ifEmpty { "free" }}"),
                shape = MaterialTheme.shapes.small, color = scheme.surface,
                border = androidx.compose.foundation.BorderStroke(if (cur) 2.dp else 1.dp, if (cur) scheme.primary else scheme.outlineVariant)) {
                Row(Modifier.height(IntrinsicSize.Min).padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GroupStrip(groups)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OneLineText(record.session.trainingLabel, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                            when {
                                status == SessionStatus.ACTIVE -> StateTag("训练中", strong = true)
                                status == SessionStatus.PAUSED -> StateTag("已暂停", strong = false)
                                done -> StateTag("已完成", strong = false)
                                else -> OneLineText("已停止", Modifier.background(scheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            val sets = if (record.session.templateId != null) "${record.completedSetCount}/${record.plannedSetCount}" else "${record.completedSetCount}"
                            Text(sets, style = pixelNumber(16.sp), color = scheme.onSurface)
                            Text("组 · ", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            Text("${record.elapsedDuration(Instant.now()).toMinutes()}", style = pixelNumber(16.sp), color = scheme.onSurface)
                            Text("分钟", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    LineIcon(Symbol.ARROW, Modifier.size(11.dp), scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * History entry: a tonal pill with the pixel clock. On tap the clock turns once in four 90° steps
 * (quarter turns keep the pixels square), then the history page opens.
 */
@Composable
private fun HistoryEntry(onHistory: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = fitHaptics()
    val turn = remember { androidx.compose.animation.core.Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    Surface(
        onClick = {
            if (!busy) scope.launch {
                busy = true; haptics.tap()
                turn.snapTo(0f); turn.animateTo(360f, androidx.compose.animation.core.tween(280, easing = StepEasing(4)))
                busy = false; onHistory()
            }
        },
        modifier = Modifier.testTag("open-history").heightIn(min = 40.dp),
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon(Symbol.CLOCK, Modifier.size(20.dp).graphicsLayer { rotationZ = turn.value })
            OneLineText("历史", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
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

/**
 * Today's plan or session, set as the card's title: a teal pixel tag for a planned day, then the
 * name in a large bold face with an optional quieter second line.
 */
@Composable
private fun TodayHeadline(title: String, detail: String?, planned: Boolean, postponed: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (postponed) OneLineText("今天已推迟", Modifier.background(MaterialTheme.colorScheme.surfaceVariant, PixelShapeSmall)
            .padding(horizontal = 8.dp, vertical = 2.dp).testTag("home-postponed"),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (planned) OneLineText("今日计划", Modifier.background(MaterialTheme.colorScheme.primary, PixelShapeSmall)
            .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onPrimary)
        OneLineText(title, Modifier.testTag("home-headline"),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        detail?.let { OneLineText(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private val MuscleGroup.short: String get() = when (this) {
    MuscleGroup.CHEST -> "胸"; MuscleGroup.BACK -> "背"; MuscleGroup.LEGS -> "腿"
    MuscleGroup.SHOULDERS -> "肩"; MuscleGroup.ARMS -> "臂"; MuscleGroup.CORE -> "核心"
}

/** One line for the week popup: "自由训练 · 胸/肩/背 · 12 组"; days without sets fall back to the plan or a rest note. */
private fun weekDayInfo(date: LocalDate, weekly: List<Workout>, catalog: List<Exercise>, plan: TrainingPlan?,
    templates: List<WorkoutTemplate>): String {
    val done = weekly.filter { it.session.localWorkoutDate == date && it.isRecordedTraining() && it.completedSetCount > 0 }
    if (done.isEmpty()) {
        val planned = plan?.dayIndex(date)?.let { plan.templateIds[it] }?.let { id -> templates.find { it.id == id }?.name }
        return when {
            date.isAfter(LocalDate.now()) -> planned?.let { "计划：$it" } ?: "暂无安排"
            else -> "未训练"
        }
    }
    val groups = done.flatMap { it.exercises }.filter { e -> e.sets.any { it.status == SetStatus.COMPLETED } }
        .mapNotNull { e -> catalog.find { it.id == e.exerciseId } }
        .flatMap { it.primaryMuscles }.map { it.group }.distinct()
    return buildList {
        add(done.map { it.session.trainingLabel }.distinct().joinToString("+"))
        if (groups.isNotEmpty()) add(groups.joinToString("/") { it.short })
        add("${done.sumOf { it.completedSetCount }} 组")
    }.joinToString(" · ")
}

/** Places the popup centred above its anchor, kept inside the window, flipping below when there is no room. */
private class AboveAnchor(private val gap: Int) : androidx.compose.ui.window.PopupPositionProvider {
    override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val above = anchorBounds.top - popupContentSize.height - gap
        return androidx.compose.ui.unit.IntOffset(x, if (above >= 0) above else anchorBounds.bottom + gap)
    }
}

/** Small pixel-edged bubble with a day's summary; it pops in over three frames and closes on any outside tap. */
@Composable
internal fun DayBubble(text: String, tag: String = "week-day-info", onDismiss: () -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val grow = remember { androidx.compose.animation.core.Animatable(.6f) }
    LaunchedEffect(text) { grow.snapTo(.6f); grow.animateTo(1f, androidx.compose.animation.core.tween(120, easing = StepEasing(3))) }
    androidx.compose.ui.window.Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        Surface(Modifier.testTag(tag).graphicsLayer { scaleX = grow.value; scaleY = grow.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f) },
            shape = PixelShapeSmall, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp) {
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Seven columns of stacked cells, one cell per four finished sets (max five); today is outlined in orange.
 * Tapping or long-pressing a day shows its summary in a bubble until the next tap elsewhere.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WeekCard(dates: Map<LocalDate, TrainingDay>, info: (LocalDate) -> String) {
    var open by remember { mutableStateOf<LocalDate?>(null) }
    val haptics = fitHaptics()
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
                // An outside tap closes the bubble before the next click lands, so a tap always (re)opens this day.
                val show = { haptics.tick(); open = date }
                Column(Modifier.weight(1f).testTag("week-day-$index")
                    .combinedClickable(interactionSource = null, indication = null, onClick = show, onLongClick = { if (open != date) show() })
                    .semanticsLabel("${listOf("一","二","三","四","五","六","日")[index]}：完成 $count 组"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The popup sits in a Box with the cells so it does not take a gap in the spaced column.
                    Box(Modifier.fillMaxWidth()) { if (open == date) DayBubble(info(date)) { if (open == date) open = null }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(5) { level ->
                            val on = 4 - level < lit
                            Box(Modifier.fillMaxWidth().height(7.dp)
                                .background(when { on -> MaterialTheme.colorScheme.primary; date.isAfter(today) -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.secondaryContainer })
                                .then(if (isToday && !on) Modifier.border(1.dp, fitColors.orange) else Modifier))
                        }
                    } }
                    Text(if (isToday) "今天" else listOf("一","二","三","四","五","六","日")[index],
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                        color = if (isToday) fitColors.orangeDeep else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
