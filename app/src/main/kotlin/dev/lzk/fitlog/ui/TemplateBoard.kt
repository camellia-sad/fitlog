package dev.lzk.fitlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/*
 * The template page as a notice board: the training plan is a ruled timetable taped across the top, each template a
 * sticky note pinned below it. Material, fixing and colour differ so the two never read as the same kind of thing.
 */

/** One muscle group's share of a template's target sets. */
internal data class GroupShare(val group: MuscleGroup, val share: Float)

internal fun WorkoutTemplate.composition(catalog: List<Exercise>): List<GroupShare> = exercises.composition(catalog)

/** Composition by target sets, largest share first; exercises without a known main muscle are left out. */
internal fun List<TemplateExercise>.composition(catalog: List<Exercise>): List<GroupShare> {
    val sets = LinkedHashMap<MuscleGroup, Int>()
    forEach { item ->
        catalog.find { it.id == item.exerciseId }?.primaryMuscles?.firstOrNull()?.group?.let { sets[it] = (sets[it] ?: 0) + item.targetSetCount }
    }
    val total = sets.values.sum().takeIf { it > 0 } ?: return emptyList()
    return sets.entries.sortedByDescending { it.value }.map { GroupShare(it.key, it.value / total.toFloat()) }
}

/** Pixel strip split by each group's share of the sets. */
@Composable
internal fun CompositionBar(mix: List<GroupShare>, modifier: Modifier = Modifier) {
    Row(modifier) {
        if (mix.isEmpty()) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
        mix.forEach { Box(Modifier.weight(it.share).fillMaxHeight().background(it.group.color())) }
    }
}

/** The template's short name on a small chip outlined in its main group colour; the same code a timetable cell shows. */
@Composable
internal fun ShortNameChip(text: String, hue: Color, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 24.dp).widthIn(min = 30.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .6f), PixelShapeSmall)
        .border(1.5.dp, hue, PixelShapeSmall).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp),
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
}

@Composable @ReadOnlyComposable
private fun paperShadow(): Color = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color.Black.copy(alpha = .35f) else Color.Black.copy(alpha = .10f)

/** A strip of translucent pixel washi tape with zig-zag torn ends. */
@Composable
private fun Tape(modifier: Modifier) {
    val color = fitColors.orange.copy(alpha = .38f)
    Canvas(modifier.size(46.dp, 16.dp)) {
        val tooth = 3.dp.toPx(); val depth = 2.dp.toPx()
        val path = Path().apply {
            moveTo(0f, depth); var x = 0f; var up = true
            while (x < size.width) { x = (x + tooth).coerceAtMost(size.width); lineTo(x, if (up) 0f else depth); up = !up }
            lineTo(size.width, size.height); x = size.width; up = true
            while (x > 0f) { x = (x - tooth).coerceAtLeast(0f); lineTo(x, if (up) size.height - depth else size.height); up = !up }
            close()
        }
        drawPath(path, color)
    }
}

/* ---------------- plan timetable ---------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlanTimetable(plan: TrainingPlan?, templates: List<WorkoutTemplate>, shortNames: Map<String, String>, catalog: List<Exercise>,
    onEdit: () -> Unit, onDelete: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val title = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    Box(Modifier.fillMaxWidth().padding(top = 7.dp).testTag("plan-card")) {
        Column(Modifier.fillMaxWidth().background(scheme.surface, PixelShapeLarge).border(1.dp, scheme.outlineVariant, PixelShapeLarge)
            .drawBehind {
                // Ruled paper below the heading.
                val line = 22.dp.toPx(); var y = 44.dp.toPx() + line
                while (y < size.height - 2) { drawRect(scheme.outlineVariant, Offset(0f, y), Size(size.width, 1.dp.toPx())); y += line }
            }
            .padding(top = 14.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("健身计划", style = title, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (plan != null) {
                    // Cycle length: the number in the pixel face, “天循环” set exactly like the title.
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${plan.cycleDays} 天循环" },
                        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${plan.cycleDays}", Modifier.alignByBaseline(), style = pixelNumber(27.sp), color = scheme.onSurface, maxLines = 1)
                        Text("天循环", Modifier.alignByBaseline(), style = title, maxLines = 1)
                    }
                    PlanAction(Symbol.EDIT, "编辑", "编辑计划", enabled = templates.isNotEmpty(), tag = "plan-edit", onClick = onEdit)
                    PlanAction(Symbol.TRASH, null, "删除计划", tag = "plan-delete") { deleting = true }
                }
            }
            if (plan == null) Column(Modifier.padding(horizontal = 14.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelButton(onClick = onEdit, enabled = templates.isNotEmpty(), minHeight = 44.dp, modifier = Modifier.fillMaxWidth()) {
                    LineIcon(Symbol.CLOCK, Modifier.size(14.dp), LocalContentColor.current); Text("设置健身计划")
                }
                if (templates.isEmpty()) Text("先创建模板，再安排每天的训练。", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            } else DayStrip(plan, templates, shortNames, catalog)
        }
        Tape(Modifier.align(Alignment.TopStart).padding(start = 14.dp).offset(y = (-7).dp).rotate(-4f))
        Tape(Modifier.align(Alignment.TopEnd).padding(end = 14.dp).offset(y = (-7).dp).rotate(3f))
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("移除健身计划？") },
        text = { Text("模板和已有训练记录会保留。") },
        confirmButton = { TextButton(onClick = { onDelete(); deleting = false }) { Text("移除计划") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
private fun PlanAction(symbol: Symbol, text: String?, label: String, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).testTag(tag)
        .semantics { contentDescription = label }, shape = RoundedCornerShape(8.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 8.dp).alpha(if (enabled) 1f else .4f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            LineIcon(symbol, Modifier.size(14.dp), scheme.onSurfaceVariant)
            text?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

private val CellWidth = 46.dp
private val CellGap = 6.dp
private val StripPadding = 14.dp

/**
 * One cell per plan day. Today stands up off the sheet (orange flag, lifted block on an orange hard shadow), days
 * already behind it in this cycle are faded, and the strip opens with today centred when it outgrows the card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayStrip(plan: TrainingPlan, templates: List<WorkoutTemplate>, shortNames: Map<String, String>, catalog: List<Exercise>) {
    val today = LocalDate.now()
    val todayIndex = plan.dayIndex(today)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptics = fitHaptics()
    var open by remember { mutableStateOf<Int?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val viewport = with(density) { maxWidth.roundToPx() }
        LaunchedEffect(plan.cycleDays, todayIndex, viewport) {
            val index = todayIndex ?: return@LaunchedEffect
            val start = with(density) { (StripPadding + (CellWidth + CellGap) * index).roundToPx() }
            val cell = with(density) { CellWidth.roundToPx() }
            snapshotFlowMax(scroll)
            scroll.scrollTo((start - (viewport - cell) / 2).coerceIn(0, scroll.maxValue))
        }
        Row(Modifier.fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 16.dp.toPx()
                if (scroll.canScrollBackward) drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade), blendMode = BlendMode.DstIn)
                if (scroll.canScrollForward) drawRect(Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width), blendMode = BlendMode.DstIn)
            }
            .horizontalScroll(scroll).padding(horizontal = StripPadding).padding(top = 18.dp, bottom = 6.dp).testTag("plan-strip"),
            horizontalArrangement = Arrangement.spacedBy(CellGap)) {
            plan.templateIds.forEachIndexed { index, id ->
                val template = id?.let { templateId -> templates.find { it.id == templateId } }
                val isToday = index == todayIndex
                val past = todayIndex != null && index < todayIndex
                val info = when {
                    id == null -> "第 ${index + 1} 天 · 休息日"
                    template == null -> "第 ${index + 1} 天 · 模板已删除"
                    else -> "${template.name} · ${template.exercises.size} 个动作 · ${template.exercises.sumOf { it.targetSetCount }} 组"
                }
                val show = { haptics.tick(); open = index }
                Column(Modifier.width(CellWidth).testTag("plan-cell-$index")
                    .combinedClickable(interactionSource = null, indication = null, onClick = show, onLongClick = { if (open != index) show() })
                    .semantics(mergeDescendants = true) {
                        contentDescription = "第 ${index + 1} 天：${template?.name ?: if (id == null) "休息" else "模板已删除"}${if (isToday) "（今天）" else ""}"
                    },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.height(22.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        if (isToday) TodayFlag() else Text(String.format(java.util.Locale.ROOT, "%02d", index + 1), Modifier.alpha(if (past) .5f else 1f),
                            style = pixelNumber(15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box(Modifier.fillMaxWidth()) {
                        if (open == index) DayBubble(info, tag = "plan-day-info") { if (open == index) open = null }
                        DayBlock(id, template, template?.let { shortNames[it.id] }, template?.composition(catalog).orEmpty(), isToday,
                            Modifier.alpha(if (past) .5f else 1f))
                    }
                }
            }
        }
    }
}

/** Waits until the scroll state knows its range (it reports Int.MAX_VALUE before the first measure). */
private suspend fun snapshotFlowMax(scroll: androidx.compose.foundation.ScrollState) {
    snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
}

@Composable
private fun TodayFlag() {
    val deep = fitColors.orangeDeep
    val ink = if (deep.luminance() > .4f) Color(0xFF2A1A05) else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("今天", Modifier.background(deep).padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp), color = ink, maxLines = 1)
        Canvas(Modifier.size(8.dp, 4.dp)) {
            val cell = size.width / 4
            drawRect(deep, Offset.Zero, Size(size.width, size.height / 2))
            drawRect(deep, Offset(cell, size.height / 2), Size(cell * 2, size.height / 2))
        }
    }
}

/** How the short name will look in a timetable cell, for the editor. */
@Composable
internal fun ShortNamePreview(text: String, mix: List<GroupShare>, modifier: Modifier = Modifier) {
    Column(modifier.testTag("short-name-preview"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DayBlock("preview", null, text.ifBlank { "?" }, mix, isToday = false, modifier = Modifier, missing = false)
        Text("日程格", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun DayBlock(id: String?, template: WorkoutTemplate?, short: String?, mix: List<GroupShare>, isToday: Boolean, modifier: Modifier,
    missing: Boolean = template == null) {
    val scheme = MaterialTheme.colorScheme
    val orange = fitColors.orange
    val shape = PixelShapeSmall
    val tint = mix.firstOrNull()?.group?.tint() ?: scheme.surfaceVariant
    val lifted = if (isToday) Modifier.offset(y = (-4).dp).drawBehind {
        // An outline ring and a hard shadow, drawn outside the block so the cut corners stay intact.
        val ring = 2.dp.toPx()
        val outer = shape.createOutline(Size(size.width + ring * 2, size.height + ring * 2), layoutDirection, this)
        translate(-ring, -ring + 4.dp.toPx()) { drawOutline(outer, orange) }
        translate(-ring, -ring) { drawOutline(outer, orange) }
    } else Modifier
    Box(modifier.then(lifted).size(CellWidth)) {
        when {
            id == null -> Box(Modifier.fillMaxSize().drawBehind {
                drawOutline(shape.createOutline(size, layoutDirection, this), scheme.outline,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }, contentAlignment = Alignment.Center) {
                Text("休", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            }
            else -> Column(Modifier.fillMaxSize().clip(shape).background(tint)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(short ?: "?", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 14.sp),
                        color = if (missing) scheme.error else scheme.onSurface, maxLines = 1, softWrap = false)
                }
                CompositionBar(mix, Modifier.fillMaxWidth().height(5.dp))
            }
        }
    }
}

/* ---------------- sticky notes ---------------- */

private const val NOTE_ROWS = 5

@Composable
private fun foldPx() = with(LocalDensity.current) { 14.dp.toPx() }

/** Note paper: group tint, a hard shadow below and the folded corner showing in the group colour. */
@Composable
private fun Modifier.notePaper(tint: Color, hue: Color): Modifier {
    val shadow = paperShadow()
    val fold = foldPx()
    val shape = remember(fold) {
        GenericShape { size, _ ->
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - fold); lineTo(size.width - fold, size.height); lineTo(0f, size.height); close()
        }
    }
    return this.drawBehind {
        translate(top = 3.dp.toPx()) { drawOutline(shape.createOutline(size, layoutDirection, this), shadow) }
        drawOutline(shape.createOutline(size, layoutDirection, this), tint)
        drawPath(Path().apply { moveTo(size.width - fold, size.height - fold); lineTo(size.width, size.height - fold); lineTo(size.width - fold, size.height); close() },
            hue.copy(alpha = .35f))
    }
}

@Composable
private fun DashedLine(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(1.5.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }
}

/** The split type as a small tilted stamp in the pin colour. */
@Composable
private fun SplitStamp(text: String, hue: Color) {
    Text(text, Modifier.padding(top = 2.dp).rotate(-6f).background(MaterialTheme.colorScheme.surface.copy(alpha = .35f))
        .border(1.5.dp, hue).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 11.sp), color = hue, maxLines = 1)
}

@Composable
private fun Figure(value: String, unit: String) {
    Text(value, style = pixelNumber(17.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    Text(unit, Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** Board heading and the dotted board itself; [content] places the notes. */
@Composable
internal fun NoticeBoard(count: Int, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dot = scheme.primary.copy(alpha = .10f)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineIcon(Symbol.PIN, Modifier.size(16.dp), scheme.onSurfaceVariant)
            Text("我的模板", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
            Text("$count", style = pixelNumber(18.sp), color = scheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surfaceVariant.copy(alpha = .55f))
            .drawBehind {
                val step = 12.dp.toPx(); val r = 1.2.dp.toPx()
                var y = step / 2
                while (y < size.height) { var x = step / 2; while (x < size.width) { drawCircle(dot, r, Offset(x, y)); x += step }; y += step }
            }
            .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 16.dp).testTag("template-board"),
            verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
    }
}

/** Notes sit a little to the left or right in turn, like pinned by hand, but never rotate. */
internal fun Modifier.staggered(index: Int) = padding(start = if (index % 2 == 1) 10.dp else 0.dp, end = if (index % 2 == 0) 10.dp else 0.dp)

@Composable
internal fun StickyNote(template: WorkoutTemplate, catalog: List<Exercise>, short: String, planned: Boolean, canStart: Boolean, busy: Boolean,
    onStart: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val mix = remember(template, catalog) { template.composition(catalog) }
    val group = mix.firstOrNull()?.group
    val hue = group?.color() ?: scheme.outline
    val tint = group?.tint() ?: scheme.surfaceVariant
    val sets = template.exercises.sumOf { it.targetSetCount }
    Box(modifier.fillMaxWidth().testTag("template-note-${template.id}")) {
        Column(Modifier.fillMaxWidth().notePaper(tint, hue).padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShortNameChip(short, hue, Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(template.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontSize = 18.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (planned) Text("今日", Modifier.background(scheme.primary, PixelShapeSmall).padding(horizontal = 7.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onPrimary, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Figure("${template.exercises.size}", "个动作"); Spacer(Modifier.width(6.dp)); Figure("$sets", "组")
                    }
                }
                if (template.splitType != SplitType.FREE) SplitStamp(template.splitType.label(), hue)
            }
            Column {
                DashedLine(hue.copy(alpha = .45f))
                template.exercises.sortedBy { it.sortOrder }.take(NOTE_ROWS).forEachIndexed { index, item ->
                    val exercise = catalog.find { it.id == item.exerciseId }
                    NoteRow(index, exercise, item)
                    DashedLine(hue.copy(alpha = .28f))
                }
            }
            if (template.exercises.size > NOTE_ROWS) Text("还有 ${template.exercises.size - NOTE_ROWS} 个动作", style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(onClick = onStart, enabled = canStart, minHeight = 40.dp, modifier = Modifier.weight(1f).testTag("start-template-${template.id}")) {
                    LineIcon(Symbol.PLAY, Modifier.size(13.dp), LocalContentColor.current); Text("开始训练", maxLines = 1)
                }
                NoteKey(Symbol.EDIT, "编辑${template.name}", hue, enabled = !busy, tag = "edit-template-${template.id}", onClick = onEdit)
                NoteKey(Symbol.TRASH, "删除${template.name}", hue, tag = "delete-template-${template.id}", onClick = onDelete)
            }
        }
        LineIcon(Symbol.PIN, Modifier.align(Alignment.TopCenter).offset(y = (-10).dp).size(22.dp), hue)
    }
}

@Composable
private fun NoteRow(index: Int, exercise: Exercise?, item: TemplateExercise) {
    val scheme = MaterialTheme.colorScheme
    val seconds = exercise?.recordMetric == RecordMetric.SECONDS
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(String.format(java.util.Locale.ROOT, "%02d", index + 1), Modifier.width(18.dp), style = pixelNumber(15.sp), color = scheme.onSurfaceVariant, maxLines = 1)
        Box(Modifier.size(8.dp).background(exercise?.primaryMuscles?.firstOrNull()?.group?.color() ?: scheme.outline))
        Text(exercise?.nameZh ?: "已归档动作", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${item.targetSetCount}", style = pixelNumber(16.sp), color = scheme.onSurface, maxLines = 1)
            Text(" 组 × ", Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = scheme.onSurfaceVariant)
            Text("${item.targetRepMin}–${item.targetRepMax}", style = pixelNumber(16.sp), color = scheme.onSurface, maxLines = 1)
            if (seconds) Text(" 秒", Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoteKey(symbol: Symbol, label: String, hue: Color, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.padding(bottom = 4.dp).size(40.dp).testTag(tag).semantics { contentDescription = label },
        shape = PixelShapeSmall, color = scheme.surface.copy(alpha = .65f), border = BorderStroke(1.5.dp, hue.copy(alpha = .55f))) {
        Box(contentAlignment = Alignment.Center) { LineIcon(symbol, Modifier.size(15.dp).alpha(if (enabled) 1f else .4f), scheme.onSurface) }
    }
}

/** An unsaved draft: an unpinned note held by tape, dashed edge, translucent, with “继续编辑”. */
@Composable
internal fun DraftNote(draft: TemplateDraft, busy: Boolean, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val hue = fitColors.arms
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(scheme.surface.copy(alpha = .7f))
            .drawBehind {
                drawRect(scheme.outline, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(draft.name.ifBlank { "未命名模板" }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, fontSize = 18.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) { Figure("${draft.exercises.size}", "个动作") }
                }
                SplitStamp("草稿", hue)
            }
            PixelButton(onClick = onResume, enabled = !busy, minHeight = 40.dp,
                modifier = Modifier.fillMaxWidth().testTag("resume-template-draft-" + (draft.id ?: "new"))) {
                LineIcon(Symbol.EDIT, Modifier.size(13.dp), LocalContentColor.current); Text("继续编辑", maxLines = 1)
            }
        }
        Tape(Modifier.align(Alignment.TopCenter).offset(y = (-8).dp).rotate(-2f))
    }
}
