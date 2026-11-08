package dev.lzk.fitlog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.Exercise
import dev.lzk.fitlog.domain.SessionStatus
import dev.lzk.fitlog.domain.Workout
import dev.lzk.fitlog.domain.WorkoutTemplate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one way into training for the day. A dashed free card comes first (named 加练 once a template was trained
 * today), then the templates as soft cards led by a pixel strip of their muscle-group colours, today's planned one
 * first. A template already trained today shows its progress; the one running is outlined and a finished one fades.
 * The chosen card stamps a pixel check before the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplatePickerSheet(
    templates: List<WorkoutTemplate>, catalog: List<Exercise>, plannedId: String?, records: List<Workout> = emptyList(),
    onPick: (String?) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = fitHaptics()
    var chosen by remember { mutableStateOf<String?>(null) }
    fun close(then: () -> Unit = {}) = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); then() }
    val ordered = remember(templates, plannedId) { templates.sortedByDescending { it.id == plannedId } }
    val bySlot = remember(records) { records.associateBy { it.session.daySlot } }
    fun pick(slot: String) {
        if (chosen != null) return
        haptics.tick(); chosen = slot
        scope.launch { delay(220); close { onPick(slot.ifEmpty { null }) } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp).padding(bottom = 8.dp).testTag("template-picker"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                OneLineText("选择训练", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (templates.isNotEmpty()) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${templates.size}", style = pixelNumber(18.sp), color = MaterialTheme.colorScheme.primary)
                    Text("个模板", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "free") {
                    val extra = records.any { it.session.daySlot.orEmpty().isNotEmpty() }
                    FreeOption(if (extra) "加练" else "自由训练", bySlot[""], selected = chosen == "") { pick("") }
                }
                items(ordered, key = { it.id }) { template ->
                    TemplateOption(template, catalog, bySlot[template.id], selected = chosen == template.id, planned = template.id == plannedId) {
                        pick(template.id)
                    }
                }
                if (templates.isEmpty()) item(key = "empty") {
                    PixelOutlineKey(onClick = { close(onManage) }, minHeight = 48.dp, modifier = Modifier.fillMaxWidth()) {
                        LineIcon(Symbol.PLUS, Modifier.size(13.dp), LocalContentColor.current); Spacer(Modifier.width(6.dp)); Text("去创建模板")
                    }
                }
            }
            if (templates.isNotEmpty()) TextButton(onClick = { close(onManage) }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("manage-templates")) {
                LineIcon(Symbol.EDIT, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("管理模板")
            }
        }
    }
}

/** The day's free record: a dashed card, with the sets done so far once it exists. */
@Composable
private fun FreeOption(name: String, record: Workout?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val live = record?.session?.status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("training-option-free"),
        shape = MaterialTheme.shapes.medium, color = if (live || selected) scheme.primaryContainer.copy(alpha = .45f) else scheme.surface) {
        Box(Modifier.height(IntrinsicSize.Min)) {
            if (!live && !selected) Canvas(Modifier.matchParentSize()) {
                val inset = 1.dp.toPx()
                drawRoundRect(scheme.outline, topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
            } else Box(Modifier.matchParentSize().border(2.dp, scheme.primary, MaterialTheme.shapes.medium))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(6.dp).height(40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(5) { Box(Modifier.fillMaxWidth().weight(1f).background(if (it % 2 == 0) scheme.outline else androidx.compose.ui.graphics.Color.Transparent)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OneLineText(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        if (live) StateTag("训练中", strong = true)
                    }
                    if (record != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Figure("${record.completedSetCount}", "组")
                    }
                }
                SelectMark(selected)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateOption(template: WorkoutTemplate, catalog: List<Exercise>, record: Workout?, selected: Boolean, planned: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val groups = remember(template, catalog) {
        template.exercises.sortedBy { it.sortOrder }.mapNotNull { e -> catalog.find { it.id == e.exerciseId } }
            .flatMap { it.primaryMuscles }.map { it.group }.distinct()
    }
    val sets = template.exercises.sumOf { it.targetSetCount }
    val live = record?.session?.status in listOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)
    val done = record != null && !live && record.plannedSetCount > 0 && record.allSetsCompleted
    val outlined = selected || live
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("template-option-${template.id}"),
        shape = MaterialTheme.shapes.medium, color = if (outlined) scheme.primaryContainer.copy(alpha = .45f) else scheme.surface,
        border = BorderStroke(if (outlined) 2.dp else 1.dp, if (outlined) scheme.primary else scheme.outlineVariant)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(12.dp).graphicsLayer { alpha = if (done && !selected) .6f else 1f },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            GroupStrip(groups)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneLineText(template.name, Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    if (planned) OneLineText("今日计划", Modifier.background(scheme.primary, PixelShapeSmall).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onPrimary)
                    if (live) StateTag("训练中", strong = true) else if (done) StateTag("已完成", strong = false)
                }
                if (record != null) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Figure("${record.completedSetCount}/${record.plannedSetCount}", "组")
                    }
                    PixelProgress(record.completedSetCount, record.plannedSetCount, Modifier.widthIn(max = 160.dp).fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Figure("${template.exercises.size}", "个动作"); Spacer(Modifier.width(8.dp)); Figure("$sets", "组")
                    }
                    if (groups.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.take(4).forEach { MuscleChip(it.label, it) }
                    }
                }
            }
            SelectMark(selected)
        }
    }
}

/** Small square-cut status tag: teal fill for the record running now, tonal for a finished one. */
@Composable
internal fun StateTag(text: String, strong: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    OneLineText(text, modifier.background(if (strong) scheme.primary else scheme.primaryContainer, PixelShapeSmall).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (strong) scheme.onPrimary else scheme.primary)
}

@Composable
private fun Figure(value: String, unit: String) {
    Text(value, style = pixelNumber(17.sp), color = MaterialTheme.colorScheme.onSurface)
    Text(unit, Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A column of pixel blocks, one per muscle group the template trains; classification colour only. */
@Composable
internal fun GroupStrip(groups: List<MuscleGroup>) {
    Column(Modifier.width(6.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (groups.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
        groups.forEach { Box(Modifier.fillMaxWidth().weight(1f).background(it.color())) }
    }
}

/** Empty pixel square, or a teal one whose check stamps in over three frames. */
@Composable
private fun SelectMark(selected: Boolean) {
    val stamp = remember { Animatable(1f) }
    LaunchedEffect(selected) { if (selected) { stamp.snapTo(1.35f); stamp.animateTo(1f, tween(150, easing = StepEasing(3))) } }
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(24.dp).graphicsLayer { scaleX = stamp.value; scaleY = stamp.value }
        .then(if (selected) Modifier.background(scheme.primary, PixelShapeSmall) else Modifier.border(1.5.dp, scheme.outline, PixelShapeSmall)),
        contentAlignment = Alignment.Center) {
        if (selected) LineIcon(Symbol.CHECK, Modifier.size(14.dp), scheme.onPrimary)
    }
}
