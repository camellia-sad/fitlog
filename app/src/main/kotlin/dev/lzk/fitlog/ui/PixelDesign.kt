package dev.lzk.fitlog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.get
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.Muscle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/* ---------------- motion ---------------- */

/** Pixel elements move in whole frames, like the exercise GIFs; layout motion keeps smooth curves. */
class StepEasing(private val steps: Int) : Easing {
    override fun transform(fraction: Float): Float = if (fraction >= 1f) 1f else floor(fraction * steps) / steps
}

/* ---------------- shapes ---------------- */

/** Rectangle whose corners are cut in two pixel steps of [step]. */
class PixelShape(private val step: Dp = 3.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx() }.coerceAtMost(minOf(size.width, size.height) / 4)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, 2 * s); lineTo(s, 2 * s); lineTo(s, s); lineTo(2 * s, s); lineTo(2 * s, 0f)
            lineTo(w - 2 * s, 0f); lineTo(w - 2 * s, s); lineTo(w - s, s); lineTo(w - s, 2 * s); lineTo(w, 2 * s)
            lineTo(w, h - 2 * s); lineTo(w - s, h - 2 * s); lineTo(w - s, h - s); lineTo(w - 2 * s, h - s); lineTo(w - 2 * s, h)
            lineTo(2 * s, h); lineTo(2 * s, h - s); lineTo(s, h - s); lineTo(s, h - 2 * s); lineTo(0f, h - 2 * s)
            close()
        }
        return Outline.Generic(path)
    }
}

val PixelShapeSmall = PixelShape(2.dp)
val PixelShapeLarge = PixelShape(3.dp)

/* ---------------- buttons ---------------- */

enum class PixelTone { ACTION, ACHIEVE }

/**
 * Primary action. The face sinks onto its hard shadow while pressed (2 frames) and
 * gives a light tap; disabled buttons lie flat without a shadow.
 */
@Composable
fun PixelButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tone: PixelTone = PixelTone.ACTION,
    minHeight: Dp = 52.dp, content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val haptics = fitHaptics()
    LaunchedEffect(pressed) { if (pressed && enabled) haptics.tap() }
    val depth = 4.dp
    val sink by animateDpAsState(if (pressed || !enabled) depth else 0.dp, tween(if (pressed) 70 else 120, easing = StepEasing(2)), label = "按压")
    val scheme = MaterialTheme.colorScheme
    val fit = fitColors
    val face = when { !enabled -> scheme.surfaceVariant; tone == PixelTone.ACHIEVE -> fit.orange; else -> scheme.primary }
    val onFace = when { !enabled -> scheme.onSurfaceVariant.copy(alpha = .7f); tone == PixelTone.ACHIEVE -> Color(0xFF2A1A05); else -> scheme.onPrimary }
    val shadow = if (tone == PixelTone.ACHIEVE) fit.orangeDeep else fit.tealDeep
    val shape = PixelShapeLarge
    Box(modifier.clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = depth).heightIn(min = minHeight)
                .offset { IntOffset(0, sink.roundToPx()) }
                .drawBehind {
                    if (enabled) translate(top = (depth - sink).toPx()) { drawOutline(shape.createOutline(size, layoutDirection, this), shadow) }
                }
                .background(face, shape)
                .then(if (focused) Modifier.border(BorderStroke(2.dp, fit.orange), shape) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides onFace) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) { content() }
            }
        }
    }
}

/**
 * Compact hard-shadow key for secondary pixel controls (editor save / cancel, steppers, done). Like [PixelButton]
 * the face sinks onto its shadow in 2 frames while pressed; [outline] draws an outlined key instead of a filled one.
 * With [repeatOnHold], holding the key repeats [onClick] after a short pause (the release then adds no extra step).
 * Disabled keys lie flat and fade.
 */
@Composable
fun PixelKey(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    face: Color = MaterialTheme.colorScheme.primary, shadow: Color = fitColors.tealDeep, outline: Color? = null,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary, depth: Dp = 3.dp, shape: Shape = PixelShapeSmall,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp), minHeight: Dp = 36.dp, minWidth: Dp = 0.dp,
    repeatOnHold: Boolean = false, content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val haptics = fitHaptics()
    val click by rememberUpdatedState(onClick)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        repeated = false
        haptics.tap()
        if (repeatOnHold) { delay(420); repeated = true; while (true) { click(); delay(90) } }
    }
    val sink by animateDpAsState(if (pressed || !enabled) depth else 0.dp, tween(if (pressed) 70 else 120, easing = StepEasing(2)), label = "按压")
    Box(modifier.alpha(if (enabled) 1f else .4f)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) { if (repeated) repeated = false else click() }) {
        Row(
            Modifier.padding(bottom = depth).heightIn(min = minHeight).widthIn(min = minWidth)
                .offset { IntOffset(0, sink.roundToPx()) }
                .drawBehind { if (enabled) translate(top = (depth - sink).toPx()) { drawOutline(shape.createOutline(size, layoutDirection, this), shadow) } }
                .background(face, shape)
                .then(if (outline != null) Modifier.border(1.5.dp, outline, shape) else Modifier)
                .then(if (focused) Modifier.border(BorderStroke(2.dp, fitColors.orange), shape) else Modifier)
                .padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)) { content() }
            }
        }
    }
}

/** Outlined variant of [PixelKey] on the surface colour, with a hard shadow in the outline colour. */
@Composable
fun PixelOutlineKey(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    face: Color = MaterialTheme.colorScheme.surface, edge: Color = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    minHeight: Dp = 36.dp, minWidth: Dp = 0.dp, repeatOnHold: Boolean = false, content: @Composable RowScope.() -> Unit,
) = PixelKey(onClick, modifier, enabled, face = face, shadow = edge, outline = edge, contentColor = contentColor,
    contentPadding = contentPadding, minHeight = minHeight, minWidth = minWidth, repeatOnHold = repeatOnHold, content = content)

/** Set-completion box: stamps in 4 frames and throws 8 pixel sparks when it becomes checked. */
@Composable
fun PixelCheck(checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fit = fitColors
    val scheme = MaterialTheme.colorScheme
    val stamp = remember { Animatable(1f) }
    val sparks = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked && !previous) {
            launch { sparks.snapTo(0f); sparks.animateTo(1f, tween(420, easing = StepEasing(4))) }
            for (frame in listOf(.6f, 1.18f, .95f, 1f)) { stamp.snapTo(frame); delay(75) }
        }
        previous = checked
    }
    val sparkColors = listOf(scheme.primary, fit.orange, fit.success)
    Box(
        modifier.size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .drawWithContent {
                drawContent()
                val p = sparks.value
                if (p < 1f) {
                    val cell = 3.dp.toPx()
                    val reach = 10.dp.toPx() + 14.dp.toPx() * p
                    repeat(8) { i ->
                        val a = i / 8f * 2 * Math.PI
                        val c = Offset(center.x + (cos(a) * reach).toFloat(), center.y + (sin(a) * reach).toFloat())
                        drawRect(sparkColors[i % 3].copy(alpha = 1f - p), Offset(c.x - cell, c.y - cell), Size(cell * 2, cell * 2))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).scale(stamp.value)
                .background(if (checked) fit.success else scheme.surface, PixelShapeLarge)
                .then(if (checked) Modifier else Modifier.border(2.dp, if (enabled) scheme.outline else scheme.outlineVariant, PixelShapeLarge)),
            contentAlignment = Alignment.Center,
        ) { if (checked) LineIcon(Symbol.CHECK, Modifier.size(18.dp), onSuccess()) }
    }
}

/** Foreground for content on [FitColors.success]: white on the deep light-theme green, ink on the pale dark-theme one. */
@Composable @ReadOnlyComposable
fun onSuccess(): Color = if (fitColors.success.luminance() > .4f) Color(0xFF0A2B2C) else Color.White

/* ---------------- numbers & progress ---------------- */

/** Number that slides in from the direction it changed, in 2 frames. */
@Composable
fun PixelNumber(text: String, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 18.sp, color: Color = Color.Unspecified) {
    AnimatedContent(text, modifier, transitionSpec = {
        val up = (targetState.toDoubleOrNull() ?: 0.0) >= (initialState.toDoubleOrNull() ?: 0.0)
        (slideInVertically(tween(140, easing = StepEasing(2))) { if (up) it / 3 else -it / 3 } + fadeIn(tween(140, easing = StepEasing(2))))
            .togetherWith(fadeOut(tween(0)))
    }, label = "数值") { value ->
        Text(value, style = pixelNumber(size), color = color, maxLines = 1, softWrap = false)
    }
}

/** 20-cell square ring for rest countdowns; lit cells go out one step at a time. */
@Composable
fun PixelRing(fraction: Float, modifier: Modifier = Modifier, warn: Boolean = false) {
    val lit = MaterialTheme.colorScheme.primary
    val dim = lit.copy(alpha = .18f)
    val warnColor = fitColors.orange
    Canvas(modifier) {
        val unit = size.minDimension / (6 + 5 * .25f)
        val gap = unit * .25f
        val cells = buildList {
            for (x in 0..5) add(x to 0); for (y in 1..5) add(5 to y); for (x in 4 downTo 0) add(x to 5); for (y in 4 downTo 1) add(0 to y)
        }
        val on = ceil(fraction.coerceIn(0f, 1f) * cells.size).toInt()
        cells.forEachIndexed { i, (x, y) ->
            drawRect(if (i < on) (if (warn) warnColor else lit) else dim, Offset(x * (unit + gap), y * (unit + gap)), Size(unit, unit))
        }
    }
}

/** Orange confetti for records and finished exercises. Fires each time [trigger] increases. */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val fit = fitColors
    val colors = listOf(fit.orange, MaterialTheme.colorScheme.primary, fit.arms, fit.chest, fit.shoulders)
    val progress = remember { Animatable(1f) }
    val pieces = remember(trigger) {
        List(24) { Piece(.42f + Random.nextFloat() * .16f, Random.nextFloat() - .5f, .3f + Random.nextFloat() * .5f, if (Random.nextBoolean()) 180f else -180f, it % 5) }
    }
    LaunchedEffect(trigger) { progress.snapTo(0f); progress.animateTo(1f, tween(1100, easing = StepEasing(10))) }
    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val side = 7.dp.toPx()
        pieces.forEach { piece ->
            val c = Offset(size.width * (piece.x + piece.dx * p), size.height * (.18f + piece.dy * p))
            rotate(piece.turn * p, c) { drawRect(colors[piece.color].copy(alpha = 1f - p * p), Offset(c.x - side / 2, c.y - side / 2), Size(side, side)) }
        }
    }
}

private data class Piece(val x: Float, val dx: Float, val dy: Float, val turn: Float, val color: Int)

/* ---------------- labels ---------------- */

@Composable
fun PrStamp(modifier: Modifier = Modifier) {
    Text("PR", modifier.rotate(-8f).border(2.dp, fitColors.orange).background(MaterialTheme.colorScheme.surface).padding(horizontal = 5.dp, vertical = 1.dp),
        style = pixelNumber(13.sp, FontWeight.Bold), color = fitColors.orangeDeep)
}

@Composable
fun PixelBadge(text: String, modifier: Modifier = Modifier, symbol: Symbol? = null) {
    Row(modifier.background(fitColors.cream, PixelShapeSmall).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        symbol?.let { LineIcon(it, Modifier.size(12.dp), fitColors.orangeDeep) }
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = fitColors.orangeDeep, maxLines = 1)
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 8.dp)) {
        OneLineText(value, style = pixelNumber(20.sp))
        OneLineText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------------- muscle groups ---------------- */

enum class MuscleGroup(val label: String) { CHEST("胸部"), BACK("背部"), LEGS("腿臀"), SHOULDERS("肩部"), ARMS("手臂"), CORE("核心") }

val Muscle.group: MuscleGroup get() = when (this) {
    Muscle.CHEST -> MuscleGroup.CHEST; Muscle.BACK -> MuscleGroup.BACK; Muscle.SHOULDERS -> MuscleGroup.SHOULDERS
    Muscle.QUADRICEPS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.CALVES -> MuscleGroup.LEGS
    Muscle.BICEPS, Muscle.TRICEPS -> MuscleGroup.ARMS; Muscle.CORE -> MuscleGroup.CORE
}

@Composable @ReadOnlyComposable
fun MuscleGroup.color(): Color = with(fitColors) { when (this@color) {
    MuscleGroup.CHEST -> chest; MuscleGroup.BACK -> back; MuscleGroup.LEGS -> legs
    MuscleGroup.SHOULDERS -> shoulders; MuscleGroup.ARMS -> arms; MuscleGroup.CORE -> core
} }

@Composable @ReadOnlyComposable
fun MuscleGroup.tint(): Color = with(fitColors) { when (this@tint) {
    MuscleGroup.CHEST -> chestTint; MuscleGroup.BACK -> backTint; MuscleGroup.LEGS -> legsTint
    MuscleGroup.SHOULDERS -> shouldersTint; MuscleGroup.ARMS -> armsTint; MuscleGroup.CORE -> coreTint
} }

@Composable
fun MuscleChip(text: String, group: MuscleGroup, modifier: Modifier = Modifier) {
    Row(modifier.background(group.tint(), RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).background(group.color()))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/** The exercise GIFs ship on opaque pale-blue canvases whose tone varies slightly per file. */
private val GifPaper = Color(0xFFEEF3FD)
private val gifPaperCache = java.util.concurrent.ConcurrentHashMap<String, Color>()

/**
 * Exercise animation on its stage, with a stepped corner tab in the muscle-group color.
 * The stage takes each GIF's own corner color so its canvas edge does not show; once the GIFs
 * share one canvas this sampling becomes a no-op.
 */
@Composable
fun ExerciseStage(id: String, name: String, group: MuscleGroup?, modifier: Modifier = Modifier, corner: Dp = 14.dp) {
    val tab = group?.color()
    val context = androidx.compose.ui.platform.LocalContext.current
    val paper by produceState(gifPaperCache[id] ?: GifPaper, id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            gifPaperCache.getOrPut(id) {
                runCatching {
                    context.assets.open("exercise-gifs/$id.gif").use { stream ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(stream, null,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
                        bitmap?.let { Color(it[1, 1]).also { _ -> it.recycle() } }
                    }
                }.getOrNull() ?: GifPaper
            }
        }
    }
    Box(modifier.background(paper, RoundedCornerShape(corner)).padding(2.dp)) {
        ExerciseGif(id, name, Modifier.fillMaxSize())
        if (tab != null) GroupCornerTab(tab)
    }
}

/** The stepped triangle in a stage's top-left corner, coloured by the exercise's main muscle group. */
@Composable
internal fun BoxScope.GroupCornerTab(color: Color, inset: Dp = 3.dp) {
    Canvas(Modifier.align(Alignment.TopStart).padding(start = inset, top = inset).size(12.dp)) {
        val cell = floor(size.minDimension / 4f)
        for (row in 0..3) drawRect(color, Offset(0f, row * cell), Size((4 - row) * cell, cell))
    }
}

/* ---------------- chrome ---------------- */

/** Shared top bar for secondary pages: back, single-line title, optional actions. */
@Composable
fun FitTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, backLabel: String = "返回",
    subtitle: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.semanticsLabel(backLabel)) {
            LineIcon(Symbol.BACK, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
        } else Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            OneLineText(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.invoke()
        }
        actions()
    }
}

/* ---------------- wordmark ---------------- */

private enum class Ink { BRAND, TEXT, ACCENT }
private data class Glyph(val rows: List<String>, val ink: Ink, val top: Int = 0)

/** Hand-set 10-row pixel wordmark: "Fit" in teal, "Log" in ink, the dot of the i in puppy orange. */
private val Wordmark = listOf(
    Glyph(listOf("#####", "#####", "##...", "####.", "####.", "##...", "##...", "##..."), Ink.BRAND),
    Glyph(listOf("##"), Ink.ACCENT),
    Glyph(listOf("##", "##", "##", "##", "##", "##"), Ink.BRAND, top = 2),
    Glyph(listOf(".##.", ".##.", "####", ".##.", ".##.", ".##.", ".###", "..##"), Ink.BRAND),
    Glyph(listOf("##...", "##...", "##...", "##...", "##...", "##...", "#####", "#####"), Ink.TEXT),
    Glyph(listOf(".####.", "##..##", "##..##", "##..##", "##..##", ".####."), Ink.TEXT, top = 2),
    Glyph(listOf(".#####", "##..##", "##..##", "##..##", "##..##", ".#####", "....##", ".####."), Ink.TEXT, top = 2),
)
private const val WordmarkRows = 10
// Columns per glyph; the i's dot and stem share one column slot.
private val WordmarkColumns = listOf(0, 6, 6, 9, 14, 20, 27)
private const val WordmarkWidth = 33

@Composable
fun PixelWordmark(modifier: Modifier = Modifier, height: Dp = 30.dp) {
    val brand = MaterialTheme.colorScheme.primary
    val text = MaterialTheme.colorScheme.onBackground
    val accent = fitColors.orange
    val shadow = fitColors.tealDeep.copy(alpha = .35f)
    Canvas(modifier.size(height * WordmarkWidth / WordmarkRows, height).semanticsLabel("FitLog")) {
        val cell = floor(size.height / WordmarkRows).coerceAtLeast(1f)
        for (pass in 0..1) Wordmark.forEachIndexed { index, glyph ->
            val color = if (pass == 0) shadow else when (glyph.ink) { Ink.BRAND -> brand; Ink.TEXT -> text; Ink.ACCENT -> accent }
            val dy = if (pass == 0) cell else 0f
            glyph.rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, c ->
                    if (c == '#') drawRect(color, Offset((WordmarkColumns[index] + x) * cell, (glyph.top + y) * cell + dy), Size(cell, cell))
                }
            }
        }
    }
}
