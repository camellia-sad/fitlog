package dev.lzk.fitlog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*

/*
 * Today's actions: one row per exercise with a cell per set, +1 and remove keys; removal is confirmed in the row
 * itself. The home card opens it in place under its “今日动作” strip; the workout page shows it in a bottom sheet.
 */

/** The strip that opens the list on the home card: title, a pixel cell per set (done in teal), count and chevron. */
@Composable
internal fun TodayActionsStrip(workout: Workout, open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(150, easing = StepEasing(3)), label = "下拉")
    Surface(onClick = onToggle, shape = RoundedCornerShape(12.dp), color = scheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("home-today-actions")
            .semantics { contentDescription = "${if (workout.session.isExtra) "加练动作" else "今日动作"} ${workout.completedSetCount}/${workout.plannedSetCount} 组，${if (open) "收起" else "展开"}" }) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (workout.session.isExtra) "加练动作" else "今日动作", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
            SetCells(workout.completedSetCount, workout.plannedSetCount, Modifier.weight(1f))
            Text("${workout.completedSetCount}/${workout.plannedSetCount}", style = pixelNumber(18.sp), color = scheme.primary, maxLines = 1)
            LineIcon(Symbol.DOWN, Modifier.size(12.dp).rotate(turn), scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetCells(done: Int, total: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(total.coerceAtMost(40)) { Box(Modifier.weight(1f).fillMaxHeight().background(if (it < done) scheme.primary else scheme.surfaceVariant)) }
    }
}

/** The home card's dropdown body; rows drop in over a few frames. */
@Composable
internal fun TodayActionsDropdown(open: Boolean, workout: Workout, catalog: List<Exercise>, onAdd: () -> Unit, onAddSet: (String) -> Unit, onRemove: (String) -> Unit) {
    AnimatedVisibility(open, enter = expandVertically(tween(200, easing = StepEasing(4))), exit = shrinkVertically(tween(160, easing = StepEasing(3)))) {
        TodayActionsList(workout, catalog, editable = true, currentId = null, onSelect = null, onAdd = onAdd, onAddSet = onAddSet, onRemove = onRemove)
    }
}

/** The workout page's version: same list in a bottom sheet, the current exercise marked, a tap switches to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayActionsSheet(workout: Workout, catalog: List<Exercise>, currentId: String?, editable: Boolean, onClose: () -> Unit,
    onSelect: (String) -> Unit, onAdd: () -> Unit, onAddSet: (String) -> Unit, onRemove: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (workout.session.isExtra) "加练动作" else "今日动作", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                Text("${workout.completedSetCount}/${workout.plannedSetCount}", style = pixelNumber(20.sp), color = MaterialTheme.colorScheme.primary)
                Text("组", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onClose) { Text("收起") }
            }
            // A long list scrolls under it, so adding stays in reach at the top here.
            if (editable) PixelKey(onClick = onAdd, minHeight = 40.dp, fillWidth = true, modifier = Modifier.fillMaxWidth().testTag("add-today-action")) {
                LineIcon(Symbol.PLUS, Modifier.size(12.dp), LocalContentColor.current); Text("添加动作", maxLines = 1)
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                TodayActionsList(workout, catalog, editable, currentId, onSelect, onAdd, onAddSet, onRemove, showAdd = false)
            }
        }
    }
}

@Composable
internal fun TodayActionsList(workout: Workout, catalog: List<Exercise>, editable: Boolean, currentId: String?, onSelect: ((String) -> Unit)?,
    onAdd: () -> Unit, onAddSet: (String) -> Unit, onRemove: (String) -> Unit, showAdd: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    var asking by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, scheme.outlineVariant, shape).background(scheme.surface, shape)) {
        workout.exercises.forEachIndexed { index, action ->
            if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
            ActionRow(action, catalog.find { it.id == action.exerciseId }, editable, action.id == currentId, asking == action.id,
                onSelect = onSelect?.takeIf { !action.removedFromPlan }?.let { { it(action.id) } },
                onAddSet = { asking = null; onAddSet(action.id) }, onAsk = { asking = action.id }, onCancel = { asking = null },
                onRemove = { asking = null; onRemove(action.id) })
        }
        if (workout.exercises.isEmpty()) Text("还没有动作", Modifier.padding(14.dp), color = scheme.onSurfaceVariant)
        if (editable && showAdd) {
            val dash = scheme.outline
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(onClick = onAdd).testTag("add-today-action")
                .drawBehind { drawLine(dash, Offset(0f, 0f), Offset(size.width, 0f), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))) },
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                LineIcon(Symbol.PLUS, Modifier.size(12.dp), scheme.primary)
                Text("添加动作", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), color = scheme.primary)
            }
        }
    }
}

@Composable
private fun ActionRow(action: SessionExercise, exercise: Exercise?, editable: Boolean, current: Boolean, asking: Boolean,
    onSelect: (() -> Unit)?, onAddSet: () -> Unit, onAsk: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = action.sets.count { it.status == SetStatus.COMPLETED }
    val left = action.sets.size - done
    val gone = action.removedFromPlan
    val primary = scheme.primary
    Row(Modifier.fillMaxWidth()
        .background(when { asking -> lerp(scheme.errorContainer, scheme.surface, .35f); gone -> scheme.surfaceVariant.copy(alpha = .6f); else -> scheme.surface })
        .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier)
        .drawBehind { if (current) drawRect(primary, Offset.Zero, Size(3.dp.toPx(), size.height)) }
        .testTag("select-action-" + action.exerciseId)
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ExerciseThumb(action.exerciseId, action.exerciseNameSnapshot, exercise?.primaryMuscles?.firstOrNull()?.group, exercise?.equipmentType,
            Modifier.size(38.dp).alpha(if (gone) .5f else 1f), corner = 9.dp)
        if (asking) {
            Text(if (done > 0) "移除剩余 $left 组？" else "从今日移除？", Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = scheme.error, maxLines = 1)
            PixelOutlineKey(onClick = onCancel, minHeight = 32.dp, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("取消", maxLines = 1) }
            PixelOutlineKey(onClick = onRemove, minHeight = 32.dp, contentPadding = PaddingValues(horizontal = 10.dp), edge = scheme.error,
                contentColor = scheme.error, modifier = Modifier.testTag("confirm-remove-" + action.exerciseId)) { Text("移除", maxLines = 1) }
            return@Row
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(action.exerciseNameSnapshot, Modifier.alpha(if (gone) .55f else 1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val shown = if (gone) done else action.sets.size
                repeat(shown.coerceAtMost(12)) { i ->
                    Box(Modifier.size(9.dp).then(if (i < done) Modifier.background(primary) else Modifier.border(1.5.dp, scheme.outline)))
                }
                Spacer(Modifier.width(3.dp))
                if (gone) Text("已移出 · 保留 $done 组", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                else Text("$done/${action.sets.size}", style = pixelNumber(14.sp), color = scheme.onSurfaceVariant)
            }
        }
        if (editable) {
            PixelOutlineKey(onClick = onAddSet, enabled = action.sets.size < WorkoutRules.MAX_SET_COUNT, minHeight = 34.dp, minWidth = 34.dp,
                contentPadding = PaddingValues(0.dp), contentColor = primary,
                modifier = Modifier.testTag("add-action-set-" + action.exerciseId).semantics { contentDescription = "为${action.exerciseNameSnapshot}增加一组" }) {
                Text("+1", style = pixelNumber(16.sp))
            }
            if (!gone) PixelOutlineKey(onClick = onAsk, minHeight = 34.dp, minWidth = 34.dp, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("remove-action-" + action.exerciseId).semantics { contentDescription = "移除${action.exerciseNameSnapshot}" }) {
                LineIcon(Symbol.CLOSE, Modifier.size(11.dp), scheme.onSurfaceVariant)
            }
        }
    }
}
