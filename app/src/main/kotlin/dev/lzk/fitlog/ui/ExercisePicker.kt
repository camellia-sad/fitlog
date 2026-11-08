package dev.lzk.fitlog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*

/** Where the picker was opened from; it decides the title, the confirm label and whether several can be picked. */
enum class PickPurpose(val route: String, val confirm: String?) {
    TODAY("today", "添加到今日"), TEMPLATE("template", "添加到模板"), TREND("trend", null);
    companion object { fun of(route: String?) = entries.firstOrNull { it.route == route } ?: TODAY }
}

/**
 * Choose exercises for a workout, a template or a trend chart. It looks like the library (search, muscle tabs, filters,
 * stickers) but is its own page: no bottom navigation, and the back key only returns to the page that opened it.
 * Filters here are local and never touch the library tab's. [incoming] is a custom exercise just created from here.
 */
@Composable
fun ExercisePickerScreen(purpose: PickPurpose, target: String, catalog: List<Exercise>, added: Set<String>,
    incoming: String?, onIncomingTaken: () -> Unit, onBack: () -> Unit, onCustom: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val single = purpose == PickPurpose.TREND
    var query by rememberSaveable { mutableStateOf("") }
    var muscle by rememberSaveable { mutableStateOf<Muscle?>(null) }
    var equipment by rememberSaveable { mutableStateOf<EquipmentType?>(null) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    // Kept as one string so it survives the trip to the custom-exercise page and back.
    var pickedRaw by rememberSaveable { mutableStateOf("") }
    val picked = remember(pickedRaw) { pickedRaw.split('\n').filter { it.isNotEmpty() } }
    fun setPicked(ids: List<String>) { pickedRaw = ids.joinToString("\n") }
    val haptics = fitHaptics()
    LaunchedEffect(incoming) {
        val id = incoming ?: return@LaunchedEffect
        onIncomingTaken()
        if (single) onConfirm(listOf(id)) else if (id !in picked && id !in added) setPicked(picked + id)
    }
    val shown = remember(catalog, query, muscle, equipment, favorites) {
        val text = query.trim()
        catalog.filter { exercise ->
            !exercise.isArchived && (!favorites || exercise.isFavorite) && (equipment == null || exercise.equipmentType == equipment) &&
                (muscle == null || muscle in exercise.primaryMuscles || muscle in exercise.secondaryMuscles) &&
                (text.isEmpty() || exercise.nameZh.contains(text, ignoreCase = true) || exercise.nameEn.contains(text, ignoreCase = true))
        }.sortedBy { it.nameZh }
    }
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FitTopBar(if (single) "选择动作" else "添加动作", onBack, backLabel = "返回上一页") {
                TextButton(onClick = onCustom, modifier = Modifier.testTag("picker-custom")) {
                    LineIcon(Symbol.PLUS, Modifier.size(12.dp)); Spacer(Modifier.width(6.dp)); Text("自定义")
                }
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(Modifier.background(scheme.primaryContainer, PixelShapeSmall).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineIcon(if (single) Symbol.SEARCH else Symbol.PIN, Modifier.size(12.dp), scheme.primary)
                    Text(target, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = scheme.primary, maxLines = 1)
                }
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("picker-search"), singleLine = true,
                    placeholder = { Text("搜索动作中文名或英文名") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    leadingIcon = { LineIcon(Symbol.SEARCH, Modifier.size(18.dp), scheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.semanticsLabel("清空搜索")) {
                            LineIcon(Symbol.CLOSE, Modifier.size(14.dp), scheme.onSurfaceVariant)
                        }
                    }, shape = MaterialTheme.shapes.small)
                MuscleTabs(muscle, Modifier.padding(top = 10.dp)) { muscle = it }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.weight(1f).semanticsLabel("${shown.size} 个动作"), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${shown.size}", style = pixelNumber(20.sp))
                        Text("个动作", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    }
                    EquipmentToggle(equipment) { equipment = it }
                    ToolToggle(Symbol.STAR, "收藏", favorites) { favorites = !favorites }
                }
            }
            LazyColumn(Modifier.weight(1f).testTag("exercise-picker"), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (picked.isEmpty()) 20.dp else 96.dp)) {
                items(shown, key = { it.id }) { exercise ->
                    val has = exercise.id in added
                    val on = exercise.id in picked
                    PickerRow(exercise, single, has, on) {
                        haptics.tick()
                        if (single) onConfirm(listOf(exercise.id))
                        else setPicked(if (on) picked - exercise.id else picked + exercise.id)
                    }
                }
                if (shown.isEmpty()) item {
                    Text("没有符合条件的动作", Modifier.padding(vertical = 12.dp), color = scheme.onSurfaceVariant)
                }
            }
        }
        if (!single) AnimatedVisibility(picked.isNotEmpty(), Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(180, easing = StepEasing(3))) { it }, exit = slideOutVertically(tween(150, easing = StepEasing(3))) { it }) {
            PickerTray(picked.mapNotNull { id -> catalog.find { it.id == id } }, purpose.confirm.orEmpty()) { onConfirm(picked) }
        }
    }
}

@Composable
private fun PickerRow(exercise: Exercise, single: Boolean, has: Boolean, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = !has, shape = MaterialTheme.shapes.medium, color = scheme.surface,
        border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("pick-${exercise.id}").semantics { selected = on }) {
        Row(Modifier.padding(8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            ExerciseThumb(exercise.id, exercise.nameZh, exercise.primaryMuscles.firstOrNull()?.group, exercise.equipmentType,
                Modifier.size(64.dp).alpha(if (has) .5f else 1f), corner = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(exercise.nameZh, Modifier.alpha(if (has) .55f else 1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (has) Text("已添加", Modifier.background(scheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 7.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                else ExerciseTags(exercise)
            }
            if (single) LineIcon(Symbol.ARROW, Modifier.size(12.dp), scheme.onSurfaceVariant)
            else {
                val stamp = remember { Animatable(1f) }
                LaunchedEffect(on) { if (on) { stamp.snapTo(1.35f); stamp.animateTo(1f, tween(150, easing = StepEasing(3))) } }
                PixelKey(onClick = onClick, enabled = !has, minHeight = 40.dp, minWidth = 46.dp, contentPadding = PaddingValues(0.dp),
                    face = if (on) scheme.primaryContainer else scheme.primary, shadow = if (on) scheme.primary else fitColors.tealDeep,
                    outline = if (on) scheme.primary else null, contentColor = if (on) scheme.primary else scheme.onPrimary,
                    modifier = Modifier.graphicsLayer { scaleX = stamp.value; scaleY = stamp.value }
                        .semantics { contentDescription = (if (on) "取消选择" else "添加") + exercise.nameZh }) {
                    LineIcon(if (on) Symbol.CHECK else Symbol.PLUS, Modifier.size(if (on) 15.dp else 14.dp), LocalContentColor.current)
                }
            }
        }
    }
}

/** Rises from the bottom once something is picked: the picks as stacked thumbs, the count, and the confirm key. */
@Composable
private fun PickerTray(picked: List<Exercise>, confirm: String, onConfirm: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = scheme.outlineVariant)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width((30 + 22 * (picked.takeLast(4).size - 1).coerceAtLeast(0)).dp).height(30.dp)) {
                    picked.takeLast(4).forEachIndexed { index, exercise ->
                        Box(Modifier.offset(x = (22 * index).dp).size(30.dp).background(scheme.surface, MaterialTheme.shapes.small).padding(1.5.dp)) {
                            ExerciseThumb(exercise.id, exercise.nameZh, exercise.primaryMuscles.firstOrNull()?.group, exercise.equipmentType,
                                Modifier.fillMaxSize(), corner = 7.dp)
                        }
                    }
                }
                Row(Modifier.weight(1f).semanticsLabel("已选 ${picked.size} 个动作"), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("已选", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    Text("${picked.size}", style = pixelNumber(24.sp), color = scheme.onSurface)
                }
                PixelKey(onClick = onConfirm, minHeight = 42.dp, depth = 4.dp, contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.testTag("picker-confirm")) {
                    LineIcon(Symbol.PLUS, Modifier.size(13.dp), LocalContentColor.current); Text(confirm, maxLines = 1)
                }
            }
        }
    }
}
