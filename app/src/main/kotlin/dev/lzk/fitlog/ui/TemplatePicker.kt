package dev.lzk.fitlog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.Exercise
import dev.lzk.fitlog.domain.WorkoutTemplate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Template picker for the home card. Each template is a soft card led by a pixel strip of its
 * muscle-group colours; the chosen one gets a teal outline and a pixel check that stamps in
 * before the sheet closes. Today's planned template carries a small tag.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplatePickerSheet(
    templates: List<WorkoutTemplate>, catalog: List<Exercise>, selectedId: String?, plannedId: String?,
    onPick: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = fitHaptics()
    var chosen by remember { mutableStateOf(selectedId) }
    fun close(then: () -> Unit = {}) = scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss(); then() }
    val ordered = remember(templates, plannedId) { templates.sortedByDescending { it.id == plannedId } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 20.dp).padding(bottom = 8.dp).testTag("template-picker"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                OneLineText("选择训练模板", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (templates.isNotEmpty()) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${templates.size}", style = pixelNumber(18.sp), color = MaterialTheme.colorScheme.primary)
                    Text("个模板", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (templates.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                LineIcon(Symbol.LIST, Modifier.size(36.dp), MaterialTheme.colorScheme.outline)
                Text("还没有模板", style = MaterialTheme.typography.titleMedium)
                Text("把常练的动作和组数存成模板，下次一键开始。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PixelButton(onClick = { close(onManage) }, minHeight = 48.dp) {
                    LineIcon(Symbol.PLUS, Modifier.size(14.dp), LocalContentColor.current); Text("去创建模板")
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ordered, key = { it.id }) { template ->
                        TemplateOption(template, catalog, selected = chosen == template.id, planned = template.id == plannedId) {
                            if (chosen == template.id) close() else {
                                haptics.tick(); chosen = template.id; onPick(template.id)
                                scope.launch { delay(220); close() }
                            }
                        }
                    }
                }
                TextButton(onClick = { close(onManage) }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("manage-templates")) {
                    LineIcon(Symbol.EDIT, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("管理模板")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateOption(template: WorkoutTemplate, catalog: List<Exercise>, selected: Boolean, planned: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val groups = remember(template, catalog) {
        template.exercises.sortedBy { it.sortOrder }.mapNotNull { e -> catalog.find { it.id == e.exerciseId } }
            .flatMap { it.primaryMuscles }.map { it.group }.distinct()
    }
    val sets = template.exercises.sumOf { it.targetSetCount }
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("template-option-${template.id}"),
        shape = MaterialTheme.shapes.medium, color = if (selected) scheme.primaryContainer.copy(alpha = .45f) else scheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            GroupStrip(groups)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OneLineText(template.name, Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    if (planned) OneLineText("今日计划", Modifier.background(scheme.primary, PixelShapeSmall).padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onPrimary)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Figure("${template.exercises.size}", "个动作"); Spacer(Modifier.width(8.dp)); Figure("$sets", "组")
                }
                if (groups.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.take(4).forEach { MuscleChip(it.label, it) }
                }
            }
            SelectMark(selected)
        }
    }
}

@Composable
private fun Figure(value: String, unit: String) {
    Text(value, style = pixelNumber(17.sp), color = MaterialTheme.colorScheme.onSurface)
    Text(unit, Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A column of pixel blocks, one per muscle group the template trains; classification colour only. */
@Composable
private fun GroupStrip(groups: List<MuscleGroup>) {
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
