package dev.lzk.fitlog.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Page wallpaper, fixed behind scrolling content:
 * [SKY] full day-period sky (home), [HORIZON] the sky's two lowest colours as a top band (browsing pages),
 * [PLAIN] paper only (workout, where the numbers need the calmest ground).
 */
enum class BackdropKind { SKY, HORIZON, PLAIN }

@Composable
fun FitBackdrop(kind: BackdropKind, modifier: Modifier = Modifier) {
    val paper = MaterialTheme.colorScheme.background
    val fit = fitColors
    Crossfade(BackdropKey(kind, fit.sky, paper, fit.grid), modifier.fillMaxSize(), tween(600), label = "页面背景") { key ->
        BackdropCanvas(key)
    }
}

private data class BackdropKey(val kind: BackdropKind, val sky: SkyPalette, val paper: Color, val grid: Color)

@Composable
private fun BackdropCanvas(key: BackdropKey) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = with(density) { 4.dp.toPx() }.roundToInt().coerceAtLeast(1)
        val skyHeight = when (key.kind) {
            BackdropKind.SKY -> statusTop + with(density) { 206.dp.toPx() }
            BackdropKind.HORIZON -> statusTop + with(density) { 96.dp.toPx() }
            BackdropKind.PLAIN -> 0f
        }
        val cols = ceil(constraints.maxWidth.toFloat() / unit).toInt().coerceAtLeast(1)
        val rows = ceil(skyHeight / unit).toInt()
        val bitmap = remember(key, cols, rows) {
            if (rows <= 0 || key.sky.stops.isEmpty()) null
            else if (key.kind == BackdropKind.SKY) skyBitmap(key.sky, key.paper, cols, rows, decorations = true)
            else skyBitmap(SkyPalette(key.sky.stops.takeLast(2)), key.paper, cols, rows, decorations = false)
        }
        Canvas(Modifier.fillMaxSize()) {
            drawRect(key.paper)
            val skyBottom = if (bitmap != null) rows * unit.toFloat() else 0f
            bitmap?.let { drawImage(it, dstSize = IntSize(cols * unit, rows * unit), filterQuality = FilterQuality.None) }
            // Grid dots: one lattice for the whole page; inside the sky they fade in over its lower half.
            if (key.grid.alpha > 0f) {
                val step = 12.dp.toPx()
                val radius = 1.dp.toPx()
                val fadeStart = skyBottom * .5f
                val width = size.width
                var y = step / 2
                while (y < size.height) {
                    val t = if (skyBottom <= 0f || y >= skyBottom) 1f else ((y - fadeStart) / (skyBottom - fadeStart)).coerceIn(0f, 1f)
                    val fade = t * t * (3 - 2 * t)
                    if (fade > 0f) {
                        val points = buildList { var x = step / 2; while (x < width) { add(Offset(x, y)); x += step } }
                        drawPoints(points, PointMode.Points, key.grid.copy(alpha = key.grid.alpha * fade), strokeWidth = radius * 2, cap = StrokeCap.Round)
                    }
                    y += step
                }
            }
        }
    }
}

/* ---------- dithered sky bitmap (one bitmap pixel = one 4dp cell) ---------- */

private val Bayer = arrayOf(intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6), intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5))
    .map { row -> row.map { (it + .5f) / 16f } }

private fun linear(c: Float) = if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f)
private fun encode(c: Float) = (if (c <= .0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - .055f).coerceIn(0f, 1f)

/** Mix in linear light so the bridge steps look evenly spaced to the eye. */
private fun mix(a: Color, b: Color, t: Float) = Color(
    encode(linear(a.red) * (1 - t) + linear(b.red) * t), encode(linear(a.green) * (1 - t) + linear(b.green) * t),
    encode(linear(a.blue) * (1 - t) + linear(b.blue) * t))

/**
 * Sky stops fill the top 58%, then five bridge colours between the last stop and the paper fill 58–92%,
 * and the rest is paper. Ordered dithering only ever mixes neighbouring stops, so no sky-coloured
 * speck lands on flat paper.
 */
private fun skyBitmap(sky: SkyPalette, paper: Color, cols: Int, rows: Int, decorations: Boolean): ImageBitmap {
    val last = sky.stops.last()
    val bridge = (1..5).map { mix(last, paper, it / 6f) }
    val stops = sky.stops + bridge + paper + paper
    val positions = sky.stops.indices.map { if (sky.stops.size == 1) 0f else .58f * it / (sky.stops.size - 1) } +
        bridge.indices.map { .58f + .34f * (it + 1) / (bridge.size + 1) } + listOf(.92f, 1f)
    val argb = stops.map { it.toArgb() }
    val pixels = IntArray(cols * rows)
    for (y in 0 until rows) {
        val p = if (rows == 1) 0f else y.toFloat() / (rows - 1)
        var i = 0
        while (i < positions.size - 2 && p >= positions[i + 1]) i++
        val f = ((p - positions[i]) / (positions[i + 1] - positions[i])).coerceIn(0f, 1f)
        for (x in 0 until cols) pixels[y * cols + x] = if (f > Bayer[y % 4][x % 4]) argb[i + 1] else argb[i]
    }
    if (decorations) decorate(pixels, cols, rows, sky)
    return Bitmap.createBitmap(pixels, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun decorate(pixels: IntArray, cols: Int, rows: Int, sky: SkyPalette) {
    fun put(x: Int, y: Int, c: Color, alpha: Float = 1f) {
        if (x !in 0 until cols || y !in 0 until rows) return
        val under = Color(pixels[y * cols + x])
        pixels[y * cols + x] = (if (alpha >= 1f) c else Color(
            under.red + (c.red - under.red) * alpha, under.green + (c.green - under.green) * alpha, under.blue + (c.blue - under.blue) * alpha)).toArgb()
    }
    val skyRows = (rows * .58f).roundToInt()
    sky.sun?.let { sun ->
        val cx = (cols * .5f).roundToInt(); val cy = (skyRows * sky.sunY).roundToInt(); val r = maxOf(3, cols / 22)
        for (y in -r - 2..r + 2) for (x in -r - 2..r + 2) {
            val d = x * x + y * y
            if (d <= r * r + 1 && cy + y < skyRows) put(cx + x, cy + y, sun)
            else if (d <= (r + 2) * (r + 2) && (x + y) % 2 == 0) put(cx + x, cy + y, sun, .4f)
        }
    }
    sky.cloud?.let { cloud ->
        val shape = listOf("..####....", ".######.##", "##########")
        listOf(.08f to .18f, .56f to .08f, .74f to .30f).forEach { (fx, fy) ->
            val ox = (cols * fx).roundToInt(); val oy = (rows * fy).roundToInt()
            shape.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch == '#') put(ox + x, oy + y, cloud) } }
        }
    }
    sky.moon?.let { moon ->
        val cx = (cols * .56f).roundToInt(); val cy = (rows * .16f).roundToInt(); val r = maxOf(3, cols / 24)
        for (y in -r - 2..r + 2) for (x in -r - 2..r + 2) {
            val d = x * x + y * y
            val bite = (x - 2) * (x - 2) + (y - 1) * (y - 1) <= r * r - 2
            if (d <= r * r + 1 && !bite) put(cx + x, cy + y, moon)
            else if (d > r * r + 1 && d <= (r + 2) * (r + 2) && (x + y) % 2 == 0) put(cx + x, cy + y, moon, .2f)
        }
    }
    sky.stars?.let { star ->
        listOf(.05f to .10f, .15f to .28f, .24f to .06f, .34f to .20f, .44f to .36f, .68f to .08f, .80f to .22f, .90f to .06f,
            .20f to .46f, .62f to .42f, .10f to .60f, .86f to .50f, .40f to .12f, .74f to .62f, .30f to .56f, .95f to .34f)
            .forEachIndexed { i, (fx, fy) ->
                val x = (cols * fx).roundToInt(); val y = (rows * fy).roundToInt()
                put(x, y, star)
                if (i % 3 == 0) { put(x - 1, y, star, .6f); put(x + 1, y, star, .6f); put(x, y - 1, star, .6f); put(x, y + 1, star, .6f) }
            }
    }
}
