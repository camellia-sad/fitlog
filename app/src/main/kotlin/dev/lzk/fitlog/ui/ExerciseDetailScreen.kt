package dev.lzk.fitlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel

/** Full page for one exercise, opened from the library instead of a bottom sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExerciseDetailScreen(exercise: Exercise?, state: WorkoutUiState, model: WorkoutViewModel,
    onBack: () -> Unit, onHistory: (String) -> Unit, onAdd: (Exercise) -> Unit) {
    var archiving by remember { mutableStateOf(false) }
    val haptics = fitHaptics()
    val canAdd = state.workout?.session?.status != SessionStatus.PAUSED
    Column(Modifier.fillMaxSize()) {
        FitTopBar(exercise?.nameZh ?: "动作详情", onBack, backLabel = "返回动作库")
        if (exercise == null) {
            Text("这个动作已不在列表中，可在动作库的“已归档”筛选中查看。", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val group = exercise.primaryMuscles.firstOrNull()?.group
            if (ExerciseAnimations.has(exercise.id))
                ExerciseStage(exercise.id, exercise.nameZh, group, Modifier.fillMaxWidth().height(240.dp), corner = 18.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(exercise.nameEn + " · " + exercise.equipmentType.label(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (exercise.primaryMuscles.isNotEmpty() || exercise.secondaryMuscles.isNotEmpty())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        exercise.primaryMuscles.forEach { MuscleChip(it.label(), it.group) }
                        exercise.secondaryMuscles.forEach { MuscleChip(it.label() + " · 辅助", it.group) }
                    }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailAction(
                    symbol = if (exercise.isFavorite) Symbol.STAR_FILLED else Symbol.STAR,
                    label = if (exercise.isFavorite) "取消收藏" else "收藏动作",
                    active = exercise.isFavorite, activeTint = fitColors.orange, activeColor = fitColors.cream,
                    modifier = Modifier.weight(1f)) { haptics.confirm(); model.favorite(exercise) }
                DetailAction(
                    symbol = if (exercise.isArchived) Symbol.UNARCHIVE else Symbol.ARCHIVE,
                    label = if (exercise.isArchived) "恢复动作" else "归档动作",
                    active = exercise.isArchived, activeTint = MaterialTheme.colorScheme.primary, activeColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.weight(1f)) { archiving = true }
            }
            exercise.instructions?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (exercise.recordMetric == RecordMetric.REPETITIONS)
                ExerciseWeightControl(exercise, state.preferences.weightUnit, state.preferences.weightStepGrams) { model.setDefaultWeight(exercise.id, it) }
            OutlinedButton(onClick = { onHistory(exercise.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                LineIcon(Symbol.CLOCK, Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); Text("查看动作历史")
            }
            if (!exercise.isArchived) PixelButton(enabled = canAdd, onClick = { onAdd(exercise) }, modifier = Modifier.fillMaxWidth()) {
                LineIcon(Symbol.PLUS, Modifier.size(14.dp), LocalContentColor.current)
                Text(if (state.workout == null) "加入待开始训练" else "添加到本次训练")
            }
            if (!canAdd) Text("训练已暂停，请先从主页继续训练后再添加动作。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (archiving && exercise != null) AlertDialog(onDismissRequest = { archiving = false },
        title = { Text(if (exercise.isArchived) "恢复动作？" else "归档动作？") },
        text = { Text("已有训练记录与名称快照会保留。归档动作可在“已归档”筛选中恢复。") },
        confirmButton = { TextButton(onClick = { archiving = false; model.archive(exercise); onBack() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { archiving = false }) { Text("取消") } })
}

/** Icon + label action filling half a row; the active state tints the icon and fills the button. */
@Composable
private fun DetailAction(symbol: Symbol, label: String, active: Boolean, activeTint: Color, activeColor: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 52.dp), shape = MaterialTheme.shapes.small,
        color = if (active) activeColor else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            LineIcon(symbol, Modifier.size(18.dp), if (active) activeTint else MaterialTheme.colorScheme.onSurfaceVariant)
            OneLineText(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
