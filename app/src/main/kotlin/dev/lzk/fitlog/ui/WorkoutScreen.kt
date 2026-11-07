package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class EditKind { WEIGHT, REPS, SET_COUNT }
private data class NumberEdit(val kind: EditKind, val setId: String?, val initial: String)

@Composable
fun WorkoutScreen(state: WorkoutUiState, model: WorkoutViewModel, onBack: () -> Unit, onAdd: () -> Unit, onFinish: () -> Unit,
    catalog: List<Exercise> = emptyList()) {
    BackHandler(onBack = onBack)
    val workout = state.workout
    val exercise = state.selectedExercise
    val unit = state.preferences.weightUnit
    val canEdit = workout?.session?.status != SessionStatus.PAUSED
    val haptics = fitHaptics()
    val scope = rememberCoroutineScope()
    var showingActions by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<NumberEdit?>(null) }
    var showingEffort by remember { mutableStateOf(false) }
    var celebrating by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    // Sets that beat the stored best while this screen was open; the stamp is a moment, not a record field.
    var recordSetIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(model) {
        model.completedSets.collectLatest {
            showingEffort = true
            delay(840)
            showingEffort = false
        }
    }
    val setsScroll = rememberLazyListState()
    LaunchedEffect(exercise?.id) { setsScroll.requestScrollToItem(0) }
    val now by produceState(Instant.now(), workout?.session?.id) {
        while (true) { value = Instant.now(); delay(1_000) }
    }
    val definition = exercise?.let { current -> catalog.find { it.id == current.exerciseId } }
    val group = definition?.primaryMuscles?.firstOrNull()?.group
    val complete: (SetRecord) -> Unit = complete@{ record ->
        val current = exercise ?: return@complete
        val best = definition?.bestWeightGrams
        val isRecord = current.recordMetricSnapshot == RecordMetric.REPETITIONS && best != null && best > 0 && record.weightGrams > best
        model.completeSet(current.id, record.id)
        haptics.confirm()
        if (isRecord) {
            recordSetIds = recordSetIds + record.id
            confetti++
            scope.launch { delay(120); haptics.double() }
            scope.launch { celebrating = true; delay(1_400); celebrating = false }
        }
    }
    if(showingActions && workout != null) TodayActionsDialog(workout, onClose = { showingActions = false },
        onSelect = { model.selectExercise(it); showingActions = false }, onAdd = { showingActions = false; onAdd() },
        onAddSet = { if(workout.session.dailyPlanDate != null) model.addDailySet(it) else model.adjustTargetSetCount(it, 1) },
        onRemove = model::removeExercise, editable = canEdit)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { LineIcon(Symbol.BACK, Modifier.size(22.dp).semanticsLabel("返回今日"), MaterialTheme.colorScheme.onSurface) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (workout?.session?.status == SessionStatus.PAUSED) "已暂停" else "训练中", style = MaterialTheme.typography.titleMedium)
                workout?.let { Text(it.elapsedDuration(now).timerText(), color = MaterialTheme.colorScheme.primary, style = pixelNumber(15.sp)) }
            }
            if (workout != null) OutlinedButton(onClick = { model.setPaused(canEdit) }, contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.heightIn(min = 36.dp)) {
                LineIcon(if (canEdit) Symbol.PAUSE else Symbol.PLAY, Modifier.size(12.dp)); Spacer(Modifier.width(6.dp)); Text(if (canEdit) "暂停" else "恢复")
            }
            TextButton(onClick = onFinish, enabled = workout != null) { Text("结束") }
        }
        if(workout != null) {
            OutlinedButton(onClick = { showingActions = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 44.dp).testTag("workout-exercises")) {
                OneLineText("${if(workout.session.isExtra) "加练动作" else "今日动作"} · ${workout.completedSetCount}/${workout.plannedSetCount} 组", Modifier.weight(1f))
                Text("展开", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                LineIcon(Symbol.DOWN, Modifier.size(12.dp))
            }
        }
        if (workout == null || exercise == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                PixelPuppy(Modifier.size(110.dp), if (canEdit) PuppyPose.IDLE else PuppyPose.SLEEP)
                Spacer(Modifier.height(20.dp))
                Text(if (workout == null) "准备好就开始吧" else "先选一个动作吧", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("每一组进步，从一个动作开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                PixelButton(onClick = onAdd, enabled = workout != null && canEdit, modifier = Modifier.widthIn(min = 180.dp)) { Text("选择动作") }
            }
            return@Box
        }
        val rest = workout.restRemainingSeconds(now)
        RestBand(rest, (exercise.restSecondsSnapshot ?: state.preferences.restSeconds).toLong(), onSkip = { model.skipRest() })
        val puppyPose = when {
            !canEdit -> PuppyPose.SLEEP
            celebrating -> PuppyPose.CELEBRATE
            showingEffort -> PuppyPose.SWEAT
            rest > 0 -> PuppyPose.REST
            else -> PuppyPose.LIFT
        }
        LazyColumn(Modifier.weight(1f).testTag("workout-sets"), state = setsScroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SoftCard(Modifier.testTag("workout-exercise-card")) {
                    // The GIF's own character is the puppy, so the state mascot only appears when there is no GIF.
                    // Custom exercises show the user's photo here when they have one; the default art stays in the library.
                    ExerciseThumb(exercise.exerciseId, exercise.exerciseNameSnapshot, group, null,
                        Modifier.fillMaxWidth().height(156.dp), fallback = false)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!ExerciseAnimations.has(exercise.exerciseId)) PixelPuppy(Modifier.size(44.dp), puppyPose)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            OneLineText(exercise.exerciseNameSnapshot, style = MaterialTheme.typography.titleLarge)
                            Text("${exercise.targetRepMinSnapshot}–${exercise.targetRepMaxSnapshot} ${exercise.recordMetricSnapshot.unit} / 组",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        definition?.primaryMuscles?.firstOrNull()?.let { muscle -> MuscleChip(muscle.label(), muscle.group) }
                    }
                    val completed = exercise.sets.count { it.status == SetStatus.COMPLETED }
                    Row(Modifier.fillMaxWidth()) {
                        Text("动作进度", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        Text("$completed / ${exercise.sets.size} 组", color = MaterialTheme.colorScheme.primary, style = pixelNumber(14.sp))
                    }
                    PixelProgress(completed, exercise.sets.size, Modifier.fillMaxWidth())
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("目标组数", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { haptics.tick(); model.adjustTargetSetCount(exercise.id, -1) }, enabled = canEdit && exercise.sets.size > 1) {
                        LineIcon(Symbol.MINUS, Modifier.size(16.dp).semanticsLabel("减少目标组数"))
                    }
                    TextButton(onClick = { editor = NumberEdit(EditKind.SET_COUNT, null, exercise.sets.size.toString()) }, enabled = canEdit) {
                        PixelNumber(exercise.sets.size.toString(), Modifier.semanticsLabel("输入目标组数"), size = 18.sp)
                    }
                    IconButton(onClick = { haptics.tick(); model.adjustTargetSetCount(exercise.id, 1) }, enabled = canEdit && exercise.sets.size < WorkoutRules.MAX_SET_COUNT) {
                        LineIcon(Symbol.PLUS, Modifier.size(16.dp).semanticsLabel("增加目标组数"))
                    }
                }
            }
            val nextId = exercise.sets.firstOrNull { it.status == SetStatus.PLANNED }?.id
            items(exercise.sets, key = { it.id }) { record ->
                SetRecordCard(
                    record = record, unit = unit, recordMetric=exercise.recordMetricSnapshot,canEdit = canEdit, step = state.preferences.weightStepGrams,
                    current = record.id == nextId, isRecord = record.id in recordSetIds && record.status == SetStatus.COMPLETED,
                    onWeight = { editor = NumberEdit(EditKind.WEIGHT, record.id, WeightUnits.format(record.weightGrams, unit, editing = true)) },
                    onReps = { editor = NumberEdit(EditKind.REPS, record.id, record.reps.toString()) },
                    onWeightDelta = { haptics.tick(); model.adjustDraft(exercise.id, record.id, weightDeltaGrams = it) },
                    onRepsDelta = { haptics.tick(); model.adjustDraft(exercise.id, record.id, repsDelta = it) },
                    onToggle = {
                        if (record.status == SetStatus.COMPLETED) { haptics.tick(); recordSetIds = recordSetIds - record.id; model.undoSet(exercise.id, record.id) }
                        else complete(record)
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("上次记录", style = MaterialTheme.typography.titleSmall)
                    val previous = state.lastPerformance
                    Text(
                        previous?.sets?.joinToString("  ·  ") {it.displayValue(exercise.recordMetricSnapshot,unit)}
                            ?: "还没有这个动作的完成记录",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val next = exercise.sets.firstOrNull { it.status == SetStatus.PLANNED }
        val ground = MaterialTheme.colorScheme.background
        Box(Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, .3f to ground))) {
            PixelButton(onClick = { next?.let(complete) }, enabled = canEdit && next != null, minHeight = 56.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                    .semanticsLabel(if (next == null) "本动作已完成" else "完成当前第${next.setIndex}组")) {
                LineIcon(Symbol.CHECK, Modifier.size(18.dp), LocalContentColor.current)
                Text(if (next == null) "本动作已完成" else "完成本组")
            }
        }
    }
    ConfettiBurst(confetti, Modifier.matchParentSize())
    }
    if (exercise == null) return
    editor?.let { edit ->
        val title = when (edit.kind) {
            EditKind.WEIGHT -> "重量（${unit.label}）"; EditKind.REPS -> exercise.recordMetricSnapshot.inputLabel; EditKind.SET_COUNT -> "目标组数"
        }
        NumberInputDialog(title, edit.initial, edit.kind == EditKind.WEIGHT, onDismiss = { editor = null }) { text ->
            try {
                val current = exercise.sets.firstOrNull { it.id == edit.setId }
                when (edit.kind) {
                    EditKind.WEIGHT -> {
                        val weight = WeightUnits.parse(text, unit)
                        current?.let { model.updateDraft(exercise.id, it.id, weight, it.reps) }
                    }
                    EditKind.REPS -> {
                        val reps = text.toIntOrNull() ?: throw WorkoutException(WorkoutError.INVALID_INPUT, "请输入整数${exercise.recordMetricSnapshot.unit}数")
                        WorkoutRules.draft(0, reps)
                        current?.let { model.updateDraft(exercise.id, it.id, it.weightGrams, reps) }
                    }
                    EditKind.SET_COUNT -> {
                        val count = text.toIntOrNull() ?: throw WorkoutException(WorkoutError.INVALID_INPUT, "请输入整数组数")
                        WorkoutRules.setCount(count)
                        model.setTargetSetCount(exercise.id, count)
                    }
                }
                editor = null
                null
            } catch (error: WorkoutException) { error.message }
        }
    }
}

/** Rest countdown under the exercise selector: a 20-cell ring that blinks orange in the last 3 seconds. */
@Composable
private fun RestBand(rest: Long, planned: Long, onSkip: () -> Unit) {
    val haptics = fitHaptics()
    var previous by remember { mutableLongStateOf(rest) }
    LaunchedEffect(rest) {
        // A natural finish ticks 1 → 0; skipping jumps from a larger value and stays silent.
        if (rest == 0L && previous in 1..2) haptics.double()
        previous = rest
    }
    AnimatedVisibility(rest > 0, enter = expandVertically(tween(280)), exit = shrinkVertically(tween(220))) {
        val total = planned.coerceAtLeast(rest).coerceAtLeast(1)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("workout-rest"),
            color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                    PixelRing(rest.toFloat() / total, Modifier.fillMaxSize(), warn = rest <= 3 && rest % 2 == 1L)
                    Text(if (rest >= 60) "${rest / 60}:${(rest % 60).toString().padStart(2, '0')}" else rest.toString(),
                        style = pixelNumber(if (rest >= 60) 14.sp else 18.sp, FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    OneLineText("组间休息", style = MaterialTheme.typography.titleSmall)
                    OneLineText("喝口水，下一组马上开始", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSkip) { OneLineText("跳过休息") }
            }
        }
    }
}

@Composable
private fun SetRecordCard(
    record: SetRecord, unit: WeightUnit, canEdit: Boolean, step: Long, onWeight: () -> Unit, onReps: () -> Unit,
    onWeightDelta: (Long) -> Unit, onRepsDelta: (Int) -> Unit, onToggle: () -> Unit,
    recordMetric:RecordMetric=RecordMetric.REPETITIONS, current: Boolean = false, isRecord: Boolean = false,
) {
    val completed = record.status == SetStatus.COMPLETED
    val editable = canEdit && record.status == SetStatus.PLANNED
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    val fit = fitColors
    val border = when { completed -> fit.success.copy(alpha = .45f); current && canEdit -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.outlineVariant }
    Surface(shape = RoundedCornerShape16, color = if (completed) fit.success.copy(alpha = .07f).compositeOn(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (current && canEdit) 1.5.dp else 1.dp, border)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(if (completed) fit.success else MaterialTheme.colorScheme.secondaryContainer, PixelShapeSmall),
                    contentAlignment = Alignment.Center) {
                    Text(record.setIndex.toString(), style = pixelNumber(15.sp, FontWeight.Bold), color = if (completed) onSuccess() else MaterialTheme.colorScheme.onSurface)
                }
                Text(if (completed) "已完成" else if (current && canEdit) "当前组" else "待完成", Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = if (current && canEdit && !completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (isRecord) PrStamp(Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                PixelCheck(completed, onToggle, enabled = canEdit, modifier = Modifier.semanticsLabel(
                    if (completed) "撤销第${record.setIndex}组" else "完成第${record.setIndex}组"))
            }
            Box(Modifier.padding(end = 8.dp)) {
                val weight: @Composable (Modifier) -> Unit = { modifier ->
                    NumberStepper("重量（${unit.label}）", WeightUnits.format(record.weightGrams, unit), modifier,
                        editable, editable && record.weightGrams >= step,
                        onValue = onWeight, onMinus = { onWeightDelta(-step) }, onPlus = { onWeightDelta(step) }, tag = "第${record.setIndex}组重量")
                }
                val reps: @Composable (Modifier) -> Unit = { modifier ->
                    NumberStepper(recordMetric.inputLabel, record.reps.toString(), modifier, editable, editable && record.reps > 0,
                        onValue = onReps, onMinus = { onRepsDelta(-1) }, onPlus = { onRepsDelta(1) }, tag = if(recordMetric==RecordMetric.SECONDS) "第${record.setIndex}组秒数" else "第${record.setIndex}组次数")
                }
                if(recordMetric==RecordMetric.SECONDS) reps(Modifier.fillMaxWidth())
                else if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    weight(Modifier.fillMaxWidth()); reps(Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    weight(Modifier.weight(1f)); reps(Modifier.weight(1f))
                }
            }
        }
    }
}

private val RoundedCornerShape16 = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)

private fun Color.compositeOn(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha), green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha), alpha = 1f)

@Composable
private fun NumberStepper(
    label: String, value: String, modifier: Modifier, enabled: Boolean, minusEnabled: Boolean,
    onValue: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit, tag: String,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        Surface(color = if (enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f), shape = MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(48.dp).semanticsLabel("减少$tag")) {
                    LineIcon(Symbol.MINUS, Modifier.size(14.dp), if (minusEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
                }
                Box(Modifier.weight(1f).clickable(enabled = enabled, onClick = onValue).padding(vertical = 10.dp).semanticsLabel("输入$tag"),
                    contentAlignment = Alignment.Center) {
                    PixelNumber(value, size = 20.sp, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(48.dp).semanticsLabel("增加$tag")) {
                    LineIcon(Symbol.PLUS, Modifier.size(14.dp), if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
                }
            }
        }
    }
}

@Composable
private fun NumberInputDialog(title: String, initial: String, decimal: Boolean, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var input by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val haptics = fitHaptics()
    val shake = remember { Animatable(0f) }
    LaunchedEffect(error) {
        if (error != null) { haptics.reject(); for (x in listOf(-4f, 4f, -2f, 0f)) { shake.snapTo(x); delay(60) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(value = input, onValueChange = { input = it; error = null }, singleLine = true,
                label = { Text(title) }, isError = error != null, supportingText = { error?.let { Text(it) } },
                textStyle = pixelNumber(20.sp, FontWeight.Medium),
                modifier = Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { error = onSave(input.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
