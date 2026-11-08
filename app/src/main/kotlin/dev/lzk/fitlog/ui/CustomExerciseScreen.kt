package dev.lzk.fitlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*

/**
 * New custom exercise as a full page. A live card on top looks exactly like its future library card; equipment is a
 * row of icon tiles, muscles cycle main → assisting → off, and the record mode is reps or seconds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomExerciseScreen(saving: Boolean, message: String?, onCancel: () -> Unit, onSave: (CustomExerciseDraft) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var english by rememberSaveable { mutableStateOf("") }
    var equipment by rememberSaveable { mutableStateOf(EquipmentType.BODYWEIGHT) }
    // Each muscle as "NAME:P" or "NAME:S", joined, so the choice survives rotation.
    var rolesRaw by rememberSaveable { mutableStateOf("") }
    var seconds by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var muscleError by remember { mutableStateOf(false) }
    val roles = remember(rolesRaw) {
        rolesRaw.split(',').filter { it.contains(':') }.associate { Muscle.valueOf(it.substringBefore(':')) to it.substringAfter(':') }
    }
    val primary = roles.filterValues { it == "P" }.keys
    val secondary = roles.filterValues { it == "S" }.keys
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        EditorHeader("新建动作", "保存动作", dirty = false, saving = saving, onCancel = onCancel, onSave = {
            muscleError = primary.isEmpty()
            val draft = CustomExerciseDraft(name, english, equipment, primary, notes, secondary,
                if (seconds) RecordMetric.SECONDS else RecordMetric.REPETITIONS)
            try { draft.validate(); error = null; onSave(draft) } catch (e: Exception) { error = if (muscleError) null else e.message }
        })
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PreviewCard(Exercise("custom-preview", name.trim().ifEmpty { "动作名称" }, english, equipment, primary, secondary,
                recordMetric = if (seconds) RecordMetric.SECONDS else RecordMetric.REPETITIONS), placeholder = name.isBlank())
            OutlinedTextField(name, { name = it.take(80); error = null }, label = { Text("动作名称") }, singleLine = true, enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("custom-name"))
            OutlinedTextField(english, { english = it.take(80) }, label = { Text("英文名（选填）") }, singleLine = true, enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("custom-english"))
            (error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val shape = RoundedCornerShape(16.dp)
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)) {
                Block("器械") { EquipmentTiles(setOf(equipment)) { equipment = it } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Block("部位", legend = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Muscle.entries.forEach { muscle ->
                            RoleChip(muscle, roles[muscle]) {
                                val next = when (roles[muscle]) { null -> "P"; "P" -> "S"; else -> null }
                                val map = roles.toMutableMap().apply { if (next == null) remove(muscle) else put(muscle, next) }
                                rolesRaw = map.entries.joinToString(",") { "${it.key.name}:${it.value}" }
                                muscleError = false
                            }
                        }
                    }
                    if (muscleError) Text("至少选择一个主练部位", Modifier.testTag("custom-muscle-error"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Block("记录方式") {
                    PixelSegmented(listOf("次数", "秒数"), if (seconds) 1 else 0, tags = listOf("custom-reps", "custom-seconds")) { seconds = it == 1 }
                }
            }
            OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("动作要点（选填）") }, minLines = 2, maxLines = 5, enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("custom-notes"))
        }
    }
}

@Composable
private fun Block(title: String, legend: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            if (legend) RoleLegend()
        }
        content()
    }
}

@Composable
private fun RoleLegend() {
    val scheme = MaterialTheme.colorScheme
    val dash = scheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(10.dp).background(scheme.surfaceVariant).border(1.5.dp, dash))
            Text("主练", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(10.dp).drawBehind { drawRect(dash, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))) })
            Text("辅助", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Muscle tag cycling off → main (group tint) → assisting (dashed group outline). */
@Composable
private fun RoleChip(muscle: Muscle, role: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val group = muscle.group
    val hue = group.color()
    val shape = RoundedCornerShape(9.dp)
    Surface(onClick = onClick, shape = shape, color = if (role == "P") group.tint() else scheme.surface,
        border = when (role) { "P" -> BorderStroke(1.dp, hue); null -> BorderStroke(1.dp, scheme.outlineVariant); else -> null },
        modifier = Modifier.heightIn(min = 32.dp).testTag("custom-muscle-${muscle.name.lowercase()}")
            .semantics { stateDescription = when (role) { "P" -> "主练"; "S" -> "辅助"; else -> "未选" } }
            .then(if (role == "S") Modifier.drawBehind {
                drawRoundRect(hue, cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            } else Modifier)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).background(hue))
            Text(muscle.label(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (role == "P") FontWeight.ExtraBold else FontWeight.Normal),
                color = if (role == null) scheme.onSurfaceVariant else scheme.onSurface, maxLines = 1)
            if (role == "S") Text("辅", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
        }
    }
}

/** The card the exercise will have in the library, drawn from the form as it is filled in. */
@Composable
private fun PreviewCard(exercise: Exercise, placeholder: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp).testTag("custom-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PREVIEW", style = pixelNumber(15.sp), color = scheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                ExerciseThumb(exercise.id, exercise.nameZh, exercise.primaryMuscles.firstOrNull()?.group, exercise.equipmentType,
                    Modifier.size(64.dp), corner = 12.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(exercise.nameZh, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (placeholder) scheme.onSurfaceVariant else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ExerciseTags(exercise)
                }
            }
        }
    }
}
