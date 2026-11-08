package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseLibraryScreen(state: WorkoutUiState, model: WorkoutViewModel, onAdd: (Exercise) -> Unit, onTemplates: () -> Unit = {},
    onOpen: (Exercise) -> Unit = {}, onCustom: () -> Unit = {}) {
    val canAdd = state.workout?.session?.status != SessionStatus.PAUSED
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(model.currentSearchQuery)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OneLineText("动作库", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onTemplates, modifier = Modifier.testTag("open-templates")) {
                LineIcon(Symbol.LIST, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                OneLineText("模板")
            }
            TextButton(onClick = onCustom, modifier = Modifier.testTag("open-custom-exercise")) {
                LineIcon(Symbol.PLUS, Modifier.size(12.dp)); Spacer(Modifier.width(6.dp)); Text("自定义")
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it; model.search(it.text) },
            modifier = Modifier.fillMaxWidth().testTag("exercise-search"), singleLine = true,
            placeholder = { Text("搜索动作中文名或英文名") },
            leadingIcon = { LineIcon(Symbol.SEARCH, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (query.text.isNotEmpty()) IconButton(
                    onClick = { query = TextFieldValue(""); model.search("") },
                    modifier = Modifier.semanticsLabel("清空搜索"),
                ) { LineIcon(Symbol.CLOSE, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            shape = MaterialTheme.shapes.small,
        )
        MuscleTabs(state.muscle, Modifier.padding(top = 10.dp)) { model.muscle(it) }
        // Switches sit in one quiet row under the tabs: count on the left, icon toggles on the right.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).semanticsLabel("${state.exercises.size} 个动作"), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${state.exercises.size}", style = pixelNumber(20.sp))
                Text("个动作", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EquipmentToggle(state.equipment, model::equipment)
            ToolToggle(Symbol.STAR, "收藏", state.favoritesOnly) { model.favorites(!state.favoritesOnly) }
            ToolToggle(Symbol.ARCHIVE, "已归档", state.archived) { model.archived(!state.archived) }
        }
        if (!canAdd) Text("训练已暂停，请先从主页继续训练后再添加动作。", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(state.exercises, key = { it.id }) { exercise ->
                ExerciseRow(exercise, state.preferences.weightUnit, canAdd, onOpen = { onOpen(exercise) }, onAdd = { onAdd(exercise) })
            }
            if (state.exercises.isEmpty() && !state.loading) item {
                Text("没有符合条件的动作，可调整筛选或创建自定义动作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExerciseRow(exercise: Exercise, unit: WeightUnit, canAdd: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ExerciseThumb(exercise.id, exercise.nameZh, exercise.primaryMuscles.firstOrNull()?.group, exercise.equipmentType,
                Modifier.size(72.dp), corner = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exercise.nameZh, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (exercise.isFavorite) LineIcon(Symbol.STAR_FILLED, Modifier.size(13.dp).semanticsLabel("已收藏"), fitColors.orange)
                }
                ExerciseTags(exercise)
                val best = exercise.bestWeightGrams
                // The number is the point: small label and unit, large pixel digits.
                if (exercise.recordMetric == RecordMetric.REPETITIONS && best != null && best > 0)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("最佳", Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(WeightUnits.format(best, unit), style = pixelNumber(19.sp), color = MaterialTheme.colorScheme.primary)
                        Text(unit.label, Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
            }
            if (!exercise.isArchived) PixelButton(onClick = onAdd, enabled = canAdd, minHeight = 40.dp,
                modifier = Modifier.width(48.dp).semanticsLabel("添加${exercise.nameZh}")) {
                LineIcon(Symbol.PLUS, Modifier.size(14.dp), LocalContentColor.current)
            }
        }
    }
}

/**
 * Tag stickers under the name: the equipment on a grey pixel-cut tag with its glyph, main muscles on their
 * group tints, assisting muscles outlined only. The row scrolls sideways when the tags outgrow it.
 */
@Composable
internal fun ExerciseTags(exercise: Exercise) {
    val scheme = MaterialTheme.colorScheme
    val tag = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    val scroll = rememberScrollState()
    // While more tags wait to the right, the last few dp fade out instead of cutting a tag in half.
    Row(Modifier.fillMaxWidth()
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (scroll.canScrollForward) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to androidx.compose.ui.graphics.Color.Black, 1f to androidx.compose.ui.graphics.Color.Transparent,
                startX = size.width - 20.dp.toPx(), endX = size.width), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
        .horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.height(20.dp).background(scheme.surfaceVariant, PixelShapeSmall).padding(start = 5.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LineIcon(exercise.equipmentType.symbol(), Modifier.size(14.dp), scheme.onSurfaceVariant)
            Text(exercise.equipmentType.label(), style = tag, color = scheme.onSurface, maxLines = 1)
        }
        exercise.primaryMuscles.forEach { muscle ->
            val group = muscle.group
            Row(Modifier.height(20.dp).background(group.tint(), RoundedCornerShape(7.dp)).padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).background(group.color()))
                Text(muscle.label(), style = tag, color = scheme.onSurface, maxLines = 1)
            }
        }
        exercise.secondaryMuscles.forEach { muscle ->
            Box(Modifier.height(20.dp).border(1.dp, scheme.outlineVariant, RoundedCornerShape(7.dp)).padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center) {
                Text(muscle.label(), style = tag, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Shape for a selected muscle tab: chamfered top corners, flat bottom so it sits on the baseline. */
private object TabShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val c = with(density) { 2.dp.toPx() }
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 2 * c); lineTo(c, 2 * c); lineTo(c, c); lineTo(2 * c, c); lineTo(2 * c, 0f)
            lineTo(size.width - 2 * c, 0f); lineTo(size.width - 2 * c, c); lineTo(size.width - c, c); lineTo(size.width - c, 2 * c)
            lineTo(size.width, 2 * c); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/**
 * Muscle filter as index tabs on a baseline. Each tab carries a thick bar in its group colour; the selected
 * tab fills with the group tint and its bar steps out to full width in three frames.
 */
@Composable
internal fun MuscleTabs(selected: Muscle?, modifier: Modifier = Modifier, onSelect: (Muscle?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = fitHaptics()
    val baseline = scheme.outlineVariant
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .drawBehind { drawRect(baseline, androidx.compose.ui.geometry.Offset(0f, size.height - 1.5.dp.toPx()),
            androidx.compose.ui.geometry.Size(size.width, 1.5.dp.toPx())) }
        .testTag("muscle-tabs"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (listOf<Muscle?>(null) + Muscle.entries).forEach { muscle ->
            val on = muscle == selected
            val hue = muscle?.group?.color() ?: scheme.primary
            val tint = muscle?.group?.tint() ?: scheme.primaryContainer
            val inset by androidx.compose.animation.core.animateDpAsState(if (on) 0.dp else 10.dp,
                androidx.compose.animation.core.tween(120, easing = StepEasing(3)), label = "tab-bar")
            Box(Modifier.height(38.dp).width(IntrinsicSize.Max)
                .then(if (on) Modifier.background(tint, TabShape) else Modifier)
                .clickable(role = androidx.compose.ui.semantics.Role.Tab) { if (!on) { haptics.tick(); onSelect(muscle) } }
                .semantics { this.selected = on }) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = inset).height(if (on) 5.dp else 4.dp)
                    .background(if (on) hue else hue.copy(alpha = .6f)))
                Text(muscle?.label() ?: "全部", Modifier.align(Alignment.Center).padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** Quiet toggle for the secondary filters: icon + short label, teal outline and fill when on. */
@Composable
internal fun ToolToggle(symbol: Symbol, label: String, on: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = Modifier.height(32.dp).semantics { selected = on }, shape = MaterialTheme.shapes.small,
        color = if (on) scheme.primaryContainer else scheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) scheme.primary else scheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            LineIcon(symbol, Modifier.size(13.dp), if (on) scheme.primary else scheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
            trailing?.invoke()
        }
    }
}

/** Equipment filter: the toggle shows the chosen equipment's glyph and name; the menu lists every type with its glyph. */
@Composable
internal fun EquipmentToggle(selected: EquipmentType?, onSelect: (EquipmentType?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolToggle(selected?.symbol() ?: Symbol.DUMBBELL, selected?.label() ?: "器械", selected != null,
            trailing = { LineIcon(Symbol.DOWN, Modifier.size(9.dp), MaterialTheme.colorScheme.onSurfaceVariant) }) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部器械") }, onClick = { onSelect(null); expanded = false })
            EquipmentType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label()) }, onClick = { onSelect(type); expanded = false },
                    leadingIcon = { LineIcon(type.symbol(), Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
    }
}
