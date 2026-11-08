package dev.lzk.fitlog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun LocalDate.weekday(): String = dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE)

/**
 * Plan editor: the cycle length as a big pixel number with step keys and week presets, the start date in a month
 * sheet, a live timetable strip, then one row per day; tapping a row raises the template choice from the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanEditor(initial: TrainingPlan, templates: List<WorkoutTemplate>, catalog: List<Exercise>, saving: Boolean, message: String?,
    onBack: () -> Unit, onSave: (TrainingPlan) -> Unit) {
    var start by remember(initial) { mutableStateOf(initial.startDate) }
    var length by remember(initial) { mutableIntStateOf(initial.cycleDays) }
    var days by remember(initial) { mutableStateOf(List(14) { initial.templateIds.getOrNull(it) }) }
    var repeats by remember(initial) { mutableStateOf(initial.repeats) }
    var choosing by remember { mutableStateOf<Int?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val shortNames = remember(templates) { templates.shortNames() }
    val byId = remember(templates) { templates.associateBy { it.id } }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        EditorHeader("健身计划", "保存计划", dirty = false, saving = saving, onCancel = onBack, onSave = {
            try {
                // Moving the start date restarts the cycle, so earlier postponements no longer apply.
                val postponed = if (start == initial.startDate) initial.postponedDates.filter { !it.isBefore(start) }.toSet() else emptySet()
                val value = TrainingPlan(start, length, repeats, days.take(length), postponed)
                value.validate(); error = null; onSave(value)
            } catch (e: Exception) { error = e.message }
        })
        LazyColumn(Modifier.weight(1f).testTag("plan-editor"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "settings") {
                val shape = RoundedCornerShape(16.dp)
                Column(Modifier.fillMaxWidth().background(scheme.surface, shape).border(1.dp, scheme.outlineVariant, shape)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$length 天循环" }.testTag("plan-length"),
                                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("$length", style = pixelNumber(56.sp), color = scheme.onSurface)
                                Text("天循环", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                            }
                            PixelOutlineKey(onClick = { length = (length - 1).coerceAtLeast(1) }, enabled = length > 1, minHeight = 34.dp, minWidth = 34.dp,
                                contentPadding = PaddingValues(0.dp), repeatOnHold = true, modifier = Modifier.semantics { contentDescription = "减少一天" }) {
                                LineIcon(Symbol.MINUS, Modifier.size(14.dp), scheme.onSurface)
                            }
                            Spacer(Modifier.width(4.dp))
                            PixelOutlineKey(onClick = { length = (length + 1).coerceAtMost(14) }, enabled = length < 14, minHeight = 34.dp, minWidth = 34.dp,
                                contentPadding = PaddingValues(0.dp), repeatOnHold = true, modifier = Modifier.semantics { contentDescription = "增加一天" }) {
                                LineIcon(Symbol.PLUS, Modifier.size(14.dp), scheme.onSurface)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7 to "一周", 14 to "两周").forEach { (value, label) ->
                                val on = length == value
                                Surface(onClick = { length = value }, shape = PixelShapeSmall, color = if (on) scheme.primaryContainer else scheme.surface,
                                    border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant),
                                    modifier = Modifier.height(28.dp).semantics { selected = on }.testTag("plan-preset-$value")) {
                                    Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                                            color = if (on) scheme.primary else scheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().clickable { pickingDate = true }.testTag("plan-start").padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("开始日期", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text("${start.monthValue.toString().padStart(2, '0')}·${start.dayOfMonth.toString().padStart(2, '0')}", style = pixelNumber(24.sp), color = scheme.onSurface)
                        Text(start.weekday(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        LineIcon(Symbol.ARROW, Modifier.size(11.dp), scheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = scheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("循环执行", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        PixelSwitch(repeats, "循环执行") { repeats = it }
                    }
                }
                (error ?: message)?.let { Text(it, Modifier.padding(top = 8.dp), color = scheme.error) }
            }
            item(key = "strip") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp).testTag("plan-preview"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0 until length).forEach { index ->
                        PreviewCell(index, days[index]?.let(byId::get), days[index]?.let { shortNames[it] }, catalog, flash?.takeIf { it.first == index }?.second ?: 0)
                    }
                }
            }
            items((0 until length).toList(), key = { "day-$it" }) { index ->
                DayRow(index, start.plusDays(index.toLong()), days[index], days[index]?.let(byId::get), days[index]?.let { shortNames[it] }, catalog) { choosing = index }
            }
        }
    }
    choosing?.let { index ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        ModalBottomSheet(onDismissRequest = { choosing = null }, sheetState = sheet, containerColor = scheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 18.dp).testTag("plan-choice"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("第 ${index + 1} 天 · ${start.plusDays(index.toLong()).weekday()}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
                (templates.map<WorkoutTemplate, WorkoutTemplate?> { it } + null).forEach { template ->
                    val on = template?.id == days[index]
                    ChoiceRow(template, template?.let { shortNames[it.id] }, catalog, on) {
                        days = days.toMutableList().apply { set(index, template?.id) }
                        flash = index to ((flash?.second ?: 0) + 1)
                        scope.launch { delay(200); sheet.hide(); choosing = null }
                    }
                }
            }
        }
    }
    if (pickingDate) DatePickerSheet("开始日期", start, onDismiss = { pickingDate = false },
        labels = { date, from ->
            val offset = java.time.temporal.ChronoUnit.DAYS.between(from, date)
            if (offset < 0 || (!repeats && offset >= length)) null else days[(offset % length).toInt()]?.let { shortNames[it] } ?: "休"
        }) { start = it; pickingDate = false }
}

@Composable
private fun PreviewCell(index: Int, template: WorkoutTemplate?, short: String?, catalog: List<Exercise>, flash: Int) {
    val scheme = MaterialTheme.colorScheme
    val glow = remember { Animatable(0f) }
    LaunchedEffect(flash) { if (flash > 0) { glow.snapTo(1f); glow.animateTo(0f, tween(500, easing = StepEasing(3))) } }
    val highlight = scheme.primaryContainer
    val outline = scheme.outline
    val mix = template?.composition(catalog).orEmpty()
    Column(Modifier.size(40.dp, 52.dp)
        .then(if (template == null) Modifier.drawBehind {
            drawOutline(PixelShapeSmall.createOutline(size, layoutDirection, this), outline,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        } else Modifier.clip(PixelShapeSmall).background(mix.firstOrNull()?.group?.tint() ?: scheme.surfaceVariant))
        .drawBehind { if (glow.value > 0f) drawRect(highlight.copy(alpha = glow.value)) },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${index + 1}", Modifier.padding(top = 3.dp), style = pixelNumber(12.sp), color = scheme.onSurfaceVariant)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(short ?: "休", style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (template == null) FontWeight.Normal else FontWeight.Black,
                fontSize = if (template == null) 11.sp else 13.sp), color = if (template == null) scheme.onSurfaceVariant else scheme.onSurface, maxLines = 1)
        }
        if (template != null) CompositionBar(mix, Modifier.fillMaxWidth().height(5.dp))
    }
}

@Composable
private fun DayRow(index: Int, date: LocalDate, id: String?, template: WorkoutTemplate?, short: String?, catalog: List<Exercise>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val outline = scheme.outline
    val rest = id == null
    Row(Modifier.fillMaxWidth().clip(shape)
        .then(if (rest) Modifier.drawBehind {
            drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        } else Modifier.background(scheme.surface).border(1.dp, scheme.outlineVariant, shape))
        .clickable(onClick = onClick).testTag("plan-day-$index")
        .semantics(mergeDescendants = true) { contentDescription = "第 ${index + 1} 天：${template?.name ?: if (rest) "休息" else "模板已删除"}" }
        .padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${index + 1}", style = pixelNumber(26.sp), color = scheme.onSurface)
            Text(date.weekday(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                rest -> Text("休息", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                template == null -> Text("模板已删除", style = MaterialTheme.typography.titleSmall, color = scheme.error)
                else -> {
                    val mix = template.composition(catalog)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShortNameChip(short ?: "?", mix.firstOrNull()?.group?.color() ?: scheme.outline)
                        Text(template.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CompositionBar(mix, Modifier.fillMaxWidth().height(6.dp))
                    Text("${template.exercises.size} 个动作 · ${template.exercises.sumOf { it.targetSetCount }} 组", style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant)
                }
            }
        }
        LineIcon(Symbol.ARROW, Modifier.size(11.dp), scheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(template: WorkoutTemplate?, short: String?, catalog: List<Exercise>, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val outline = scheme.outline
    val stamp = remember { Animatable(1f) }
    LaunchedEffect(on) { if (on) { stamp.snapTo(1.4f); stamp.animateTo(1f, tween(150, easing = StepEasing(3))) } }
    Row(Modifier.fillMaxWidth().clip(shape)
        .then(when {
            on -> Modifier.background(scheme.primaryContainer).border(1.5.dp, scheme.primary, shape)
            template == null -> Modifier.drawBehind {
                drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            }
            else -> Modifier.background(scheme.surface).border(1.dp, scheme.outlineVariant, shape)
        })
        .clickable(onClick = onClick).testTag("plan-option-${template?.id ?: "rest"}").semantics { selected = on }
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val mix = template?.composition(catalog).orEmpty()
        ShortNameChip(short ?: "休", mix.firstOrNull()?.group?.color() ?: scheme.outline)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(template?.name ?: "休息", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (template != null) CompositionBar(mix, Modifier.fillMaxWidth().height(5.dp))
        }
        if (on) Box(Modifier.size(20.dp).graphicsLayer { scaleX = stamp.value; scaleY = stamp.value }.background(scheme.primary, PixelShapeSmall),
            contentAlignment = Alignment.Center) {
            LineIcon(Symbol.CHECK, Modifier.size(12.dp), scheme.onPrimary)
        }
    }
}
