package dev.lzk.fitlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private enum class EditKind { WEIGHT, REPS, SET_COUNT }
private data class NumberEdit(val kind: EditKind, val setId: String?, val initial: String)

@Composable
fun WorkoutScreen(state: WorkoutUiState, model: WorkoutViewModel, onBack: () -> Unit, onAdd: () -> Unit, onFinish: () -> Unit) {
    BackHandler(onBack = onBack)
    val workout = state.workout
    val exercise = state.selectedExercise
    val unit = state.preferences.weightUnit
    val canEdit = workout?.session?.status != SessionStatus.PAUSED
    var editor by remember { mutableStateOf<NumberEdit?>(null) }
    var showingEffort by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.completedSets.collectLatest {
            showingEffort = true
            delay(840)
            showingEffort = false
        }
    }
    val setsScroll = rememberLazyListState()
    LaunchedEffect(exercise?.id) { setsScroll.scrollToItem(0) }
    val now by produceState(Instant.now(), workout?.session?.id) {
        while (true) { value = Instant.now(); delay(1_000) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { LineIcon(Symbol.BACK, Modifier.size(24.dp).semanticsLabel("返回今日")) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (workout?.session?.status == SessionStatus.PAUSED) "已暂停" else "训练中", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                workout?.let { Text(it.elapsedDuration(now).timerText(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
            }
            if (workout != null) TextButton(onClick = { model.setPaused(canEdit) }) { Text(if (canEdit) "暂停" else "恢复") }
            TextButton(onClick = onFinish, enabled = workout != null) { Text("结束") }
        }
        if (workout == null || exercise == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                PixelPuppy(Modifier.size(110.dp), if (canEdit) PuppyPose.IDLE else PuppyPose.SLEEP)
                Spacer(Modifier.height(20.dp))
                Text(if (workout == null) "准备好就开始吧" else "先选一个动作吧", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("每一组进步，从一个动作开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAdd, enabled = workout != null && canEdit, modifier = Modifier.heightIn(min = 54.dp)) { Text("选择动作") }
            }
            return
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onAdd, enabled = canEdit, label = { Text("添加动作") }, leadingIcon = { LineIcon(Symbol.PLUS, Modifier.size(16.dp)) })
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("workout-exercises"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            workout.exercises.forEach { item ->
                FilterChip(selected = item.id == exercise.id, onClick = { model.selectExercise(item.id) },
                    label = { Text(item.exerciseNameSnapshot, maxLines = 1, softWrap = false) }, shape = RoundedCornerShape(12.dp))
            }
            }
        }
        val rest = workout.restRemainingSeconds(now)
        if (rest > 0) Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("workout-rest"),
            color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    OneLineText("组间休息", style = MaterialTheme.typography.labelLarge)
                    OneLineText("${rest / 60}:${(rest % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { model.skipRest() }) { OneLineText("跳过休息") }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("workout-sets"), state = setsScroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SoftCard(Modifier.testTag("workout-exercise-card")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                            LineIcon(Symbol.DUMBBELL, Modifier.padding(10.dp).size(24.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            OneLineText(exercise.exerciseNameSnapshot, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                            Text("${exercise.targetRepMinSnapshot}–${exercise.targetRepMaxSnapshot} 次 / 组",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        PixelPuppy(Modifier.size(40.dp), when {
                            !canEdit -> PuppyPose.SLEEP
                            showingEffort -> PuppyPose.SWEAT
                            workout.restRemainingSeconds(now) > 0 -> PuppyPose.REST
                            else -> PuppyPose.LIFT
                        })
                    }
                    val completed = exercise.sets.count { it.status == SetStatus.COMPLETED }
                    Row(Modifier.fillMaxWidth()) {
                        Text("动作进度", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        Text("$completed / ${exercise.sets.size} 组", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    PixelProgress(completed, exercise.sets.size, Modifier.fillMaxWidth())
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("目标组数", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    IconButton(onClick = { model.adjustTargetSetCount(exercise.id, -1) }, enabled = canEdit && exercise.sets.size > 1) {
                        LineIcon(Symbol.MINUS, Modifier.size(20.dp).semanticsLabel("减少目标组数"))
                    }
                    TextButton(onClick = { editor = NumberEdit(EditKind.SET_COUNT, null, exercise.sets.size.toString()) }, enabled = canEdit) {
                        Text(exercise.sets.size.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semanticsLabel("输入目标组数"))
                    }
                    IconButton(onClick = { model.adjustTargetSetCount(exercise.id, 1) }, enabled = canEdit && exercise.sets.size < WorkoutRules.MAX_SET_COUNT) {
                        LineIcon(Symbol.PLUS, Modifier.size(20.dp).semanticsLabel("增加目标组数"))
                    }
                }
            }
            items(exercise.sets, key = { it.id }) { record ->
                SetRecordCard(
                    record = record, unit = unit, canEdit = canEdit, step = state.preferences.weightStepGrams,
                    onWeight = { editor = NumberEdit(EditKind.WEIGHT, record.id, WeightUnits.format(record.weightGrams, unit, editing = true)) },
                    onReps = { editor = NumberEdit(EditKind.REPS, record.id, record.reps.toString()) },
                    onWeightDelta = { model.adjustDraft(exercise.id, record.id, weightDeltaGrams = it) },
                    onRepsDelta = { model.adjustDraft(exercise.id, record.id, repsDelta = it) },
                    onToggle = {
                        if (record.status == SetStatus.COMPLETED) model.undoSet(exercise.id, record.id)
                        else model.completeSet(exercise.id, record.id)
                    },
                )
            }
            item {
                SoftCard(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
                    Text("上次记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    val previous = state.lastPerformance
                    Text(
                        previous?.sets?.joinToString("  ·  ") { "${WeightUnits.format(it.weightGrams, unit)} ${unit.label} × ${it.reps}" }
                            ?: "还没有这个动作的完成记录",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val next = exercise.sets.firstOrNull { it.status == SetStatus.PLANNED }
        Surface(color = MaterialTheme.colorScheme.background) {
            Button(onClick = { next?.let { model.completeSet(exercise.id, it.id) } },
                enabled = canEdit && next != null, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 56.dp)
                    .semanticsLabel(if (next == null) "本动作已完成" else "完成当前第${next.setIndex}组")) {
                LineIcon(Symbol.CHECK, Modifier.size(22.dp), MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text(if (next == null) "本动作已完成" else "完成本组", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (exercise == null) return
    editor?.let { edit ->
        val title = when (edit.kind) {
            EditKind.WEIGHT -> "重量（${unit.label}）"; EditKind.REPS -> "次数"; EditKind.SET_COUNT -> "目标组数"
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
                        val reps = text.toIntOrNull() ?: throw WorkoutException(WorkoutError.INVALID_INPUT, "请输入整数次数")
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

@Composable
private fun SetRecordCard(
    record: SetRecord, unit: WeightUnit, canEdit: Boolean, step: Long, onWeight: () -> Unit, onReps: () -> Unit,
    onWeightDelta: (Long) -> Unit, onRepsDelta: (Int) -> Unit, onToggle: () -> Unit,
) {
    val completed = record.status == SetStatus.COMPLETED
    val editable = canEdit && record.status == SetStatus.PLANNED
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (completed) FitLogColors.Success.copy(alpha = .5f) else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(record.setIndex.toString(), Modifier.padding(horizontal = 13.dp, vertical = 6.dp), fontWeight = FontWeight.SemiBold)
                }
                Text(if (completed) "已完成" else "待完成", Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onToggle, enabled = canEdit, modifier = Modifier.semanticsLabel(
                    if (completed) "撤销第${record.setIndex}组" else "完成第${record.setIndex}组")) {
                    Surface(shape = CircleShape, color = if (completed) FitLogColors.Success else MaterialTheme.colorScheme.surface,
                        border = if (completed) null else BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.size(30.dp)) {
                        if (completed) LineIcon(Symbol.CHECK, Modifier.padding(5.dp), Color.White)
                    }
                }
            }
            val weight: @Composable (Modifier) -> Unit = { modifier ->
                NumberStepper("重量（${unit.label}）", WeightUnits.format(record.weightGrams, unit), modifier,
                    editable, editable && record.weightGrams >= step,
                    onValue = onWeight, onMinus = { onWeightDelta(-step) }, onPlus = { onWeightDelta(step) }, tag = "第${record.setIndex}组重量")
            }
            val reps: @Composable (Modifier) -> Unit = { modifier ->
                NumberStepper("次数", record.reps.toString(), modifier, editable, editable && record.reps > 0,
                    onValue = onReps, onMinus = { onRepsDelta(-1) }, onPlus = { onRepsDelta(1) }, tag = "第${record.setIndex}组次数")
            }
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                weight(Modifier.fillMaxWidth()); reps(Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weight(Modifier.weight(1f)); reps(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumberStepper(
    label: String, value: String, modifier: Modifier, enabled: Boolean, minusEnabled: Boolean,
    onValue: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit, tag: String,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        Surface(color = if (enabled) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f), shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(48.dp).semanticsLabel("减少$tag")) {
                    LineIcon(Symbol.MINUS, Modifier.size(18.dp), if (minusEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
                }
                Text(value, modifier = Modifier.weight(1f).clickable(enabled = enabled, onClick = onValue)
                    .padding(vertical = 12.dp).semanticsLabel("输入$tag"),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                IconButton(onClick = onPlus, enabled = enabled, modifier = Modifier.size(48.dp).semanticsLabel("增加$tag")) {
                    LineIcon(Symbol.PLUS, Modifier.size(18.dp), if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f))
                }
            }
        }
    }
}

@Composable
private fun NumberInputDialog(title: String, initial: String, decimal: Boolean, onDismiss: () -> Unit, onSave: (String) -> String?) {
    var input by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(value = input, onValueChange = { input = it; error = null }, singleLine = true,
                label = { Text(title) }, isError = error != null, supportingText = { error?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
        },
        confirmButton = { TextButton(onClick = { error = onSave(input.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
