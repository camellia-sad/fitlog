package dev.lzk.fitlog.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.ProfileBanners
import dev.lzk.fitlog.domain.SocialRepository
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sin

/** Banner choices in picker order; "" is the time-of-day sky and [BANNER_PHOTO] an image from the album. */
internal val BANNER_CHOICES = listOf("" to "时段天空", "gym" to "健身房", "track" to "跑道", "hills" to "山野", "sea" to "海边")
internal const val BANNER_PHOTO = "photo"
private const val ROWS = 26
private val BAYER = arrayOf(intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6), intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5))

/**
 * The profile card's backdrop: the person's own image, cropped to fill, or a pixel scene drawn 26 pixels tall with the
 * backdrop's 4×4 ordered dithering. The default scene is the time-of-day sky from [fitColors].
 */
@Composable
internal fun ProfileBannerArt(repository: SocialRepository?, banner: String?, modifier: Modifier = Modifier, pending: ImageBitmap? = null) {
    Box(modifier) {
        val path = banner?.takeIf { ProfileBanners.isImage(it) }
        when {
            pending != null -> Image(pending, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            path != null && repository != null -> {
                PixelScene(null, Modifier.fillMaxSize())
                SocialImage(repository, path, Modifier.fillMaxSize(), previewEnabled = false, contentScale = ContentScale.Crop, keep = true) {}
            }
            else -> PixelScene(ProfileBanners.scene(banner), Modifier.fillMaxSize())
        }
        // A soft shade under the wordmark keeps it readable on photos and bright skies.
        if (pending != null || path != null) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .22f), .5f to Color.Transparent)))
    }
}

@Composable
private fun PixelScene(scene: String?, modifier: Modifier) {
    val sky = fitColors.sky
    val paper = MaterialTheme.colorScheme.background
    var size by remember { mutableStateOf(IntSize.Zero) }
    val columns = if (size.height > 0) (ROWS * size.width / size.height.toFloat()).roundToInt().coerceIn(ROWS, 400) else 0
    val image = remember(scene, sky, paper, columns) { if (columns > 0) drawScene(scene, sky, paper, columns).asImageBitmap() else null }
    Canvas(modifier.onSizeChanged { size = it }) {
        image?.let { drawImage(it, IntOffset.Zero, IntSize(it.width, it.height), dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.None) }
    }
}

private class Painter(val bitmap: Bitmap) {
    val w = bitmap.width; val h = bitmap.height
    fun rect(x: Int, y: Int, width: Int, height: Int, color: Color) {
        val c = color.toArgb()
        for (yy in y.coerceAtLeast(0) until (y + height).coerceAtMost(h)) for (xx in x.coerceAtLeast(0) until (x + width).coerceAtMost(w)) bitmap.setPixel(xx, yy, c)
    }
    fun gradient(stops: List<Color>, bottom: Int) {
        if (stops.size < 2) { rect(0, 0, w, bottom, stops.firstOrNull() ?: Color.Gray); return }
        val band = bottom / (stops.size - 1f)
        for (y in 0 until bottom) for (x in 0 until w) {
            val f = y / band; val i = f.toInt().coerceAtMost(stops.size - 2); val t = f - i
            bitmap.setPixel(x, y, (if (t * 16 > BAYER[y % 4][x % 4]) stops[i + 1] else stops[i]).toArgb())
        }
    }
}

private fun drawScene(scene: String?, sky: SkyPalette, paper: Color, columns: Int): Bitmap {
    val p = Painter(Bitmap.createBitmap(columns, ROWS, Bitmap.Config.ARGB_8888))
    val w = columns; val h = ROWS
    when (scene) {
        "gym" -> {
            p.gradient(listOf(Color(0xFF5C6B7E), Color(0xFF66768A), Color(0xFF718196), Color(0xFF7C8CA1)), h)
            for (x in 0 until w step 12) p.rect(x, 0, 1, h - 6, Color(0xFF4A5668))
            p.rect(8, 4, 22, 9, Color(0xFFC9D3DE)); p.rect(9, 5, 20, 7, Color(0xFF9FB6CC))
            val rack = (w * .45f).toInt()
            p.rect(rack, 13, 34, 1, Color(0xFF3D4759)); p.rect(rack + 1, 14, 1, 7, Color(0xFF3D4759)); p.rect(rack + 32, 14, 1, 7, Color(0xFF3D4759))
            listOf(0xFFE9798D, 0xFF5E95E0, 0xFF43A877, 0xFFD9A42F, 0xFF9479E0).forEachIndexed { i, c ->
                val x = rack + 4 + i * 6
                p.rect(x, 10, 3, 3, Color(c)); p.rect(x, 15, 3, 3, Color(c)); p.rect(x - 1, 11, 5, 1, Color(0xFF3D4759))
            }
            for (x in 0 until w) { p.rect(x, h - 6, 1, 1, Color(0xFF8C6A4E)); p.rect(x, h - 5, 1, 5, if ((x / 6) % 2 == 1) Color(0xFFA07C5C) else Color(0xFF946F51)) }
        }
        "track" -> {
            p.gradient(listOf(Color(0xFF8DC7E8), Color(0xFFA8D4EE), Color(0xFFC3E2F3), Color(0xFFDBEDF7)), h)
            val cx = (w * .62f).toInt(); p.rect(cx, 5, 10, 2, Color.White); p.rect(cx + 2, 4, 5, 1, Color.White)
            p.rect(0, h - 11, w, 3, Color(0xFF6FAE86)); p.rect(0, h - 8, w, 8, Color(0xFFC8573F))
            p.rect(0, h - 6, w, 1, Color(0xFFF4E9E2)); p.rect(0, h - 3, w, 1, Color(0xFFF4E9E2))
            for (y in h - 8 until h) p.rect((w * .3f).toInt() + (h - y), y, 1, 1, Color(0xFFF4E9E2))
        }
        "hills" -> {
            p.gradient(listOf(Color(0xFFF2B296), Color(0xFFF6C4A8), Color(0xFFF9D5BC), Color(0xFFFBE3D0)), h)
            val sx = (w * .5f).toInt(); p.rect(sx, 9, 6, 4, Color(0xFFF79A36)); p.rect(sx + 1, 8, 4, 6, Color(0xFFF79A36))
            fun ridge(base: Int, amp: Float, f: Float, c: Long) { for (x in 0 until w) {
                val top = (base + amp * sin(x * f) + amp * .5f * sin(x * f * 2.3f)).roundToInt(); p.rect(x, h - top, 1, top, Color(c)) } }
            ridge(10, 3f, .09f, 0xFFB98C8A); ridge(6, 2f, .15f, 0xFF7E8E6A); ridge(3, 1f, .3f, 0xFF5E7552)
        }
        "sea" -> {
            p.gradient(listOf(Color(0xFF7DBFE4), Color(0xFF96CCEA), Color(0xFFB0D9F0), Color(0xFFCBE6F5)), h)
            val sx = (w * .4f).toInt(); p.rect(sx, 5, 6, 4, Color(0xFFFFD66B)); p.rect(sx + 1, 4, 4, 6, Color(0xFFFFD66B))
            for (y in h - 10 until h - 3) p.rect(0, y, w, 1, if (y % 2 == 1) Color(0xFF3E89B8) else Color(0xFF4A97C4))
            for (x in 3 until w step 9) p.rect(x, h - 8 + x % 3, 3, 1, Color(0xFFDDEFF8))
            p.rect(0, h - 3, w, 3, Color(0xFFE8D2A6)); for (x in 0 until w step 5) p.rect(x, h - 2, 2, 1, Color(0xFFD7BE8E))
        }
        else -> {
            val stops = sky.stops.ifEmpty { listOf(paper) }
            p.gradient(stops, h - 3)
            var seed = 11
            fun rnd(): Float { seed = (seed * 9301 + 49297) % 233280; return seed / 233280f }
            sky.stars?.let { star -> repeat(columns / 4) {
                val x = (rnd() * w).toInt(); val y = (rnd() * 16).toInt(); val a = .5f + rnd() * .5f
                if (x > w * .3f || y > 8) p.rect(x, y, 1, 1, star.copy(alpha = a).compositeOver(stops.first())) } }
            // Sun and moon sit right of the FITLOG mark and left of the corner control.
            val bx = (w * .38f).toInt()
            sky.moon?.let { p.rect(bx + 1, 4, 4, 6, it); p.rect(bx, 5, 6, 4, it); p.rect(bx + 3, 4, 3, 4, stops.first()) }
            sky.sun?.let { val cy = (2 + sky.sunY * 14).roundToInt(); p.rect(bx, cy, 6, 4, it); p.rect(bx + 1, cy - 1, 4, 6, it) }
            sky.cloud?.let { c -> listOf((w * .5f).toInt() to 7, (w * .68f).toInt() to 11).forEach { (x, y) -> p.rect(x, y, 10, 2, c); p.rect(x + 2, y - 1, 5, 1, c); p.rect(x + 1, y + 2, 8, 1, c) } }
            // A distant skyline, then a two-row tiled floor, both tinted from the horizon colour.
            val ground = lerp(stops.last(), Color(0xFF3D4759), .35f)
            val groundDark = lerp(stops.last(), Color(0xFF3D4759), .5f)
            val town = ground.copy(alpha = .45f).compositeOver(stops.last())
            listOf(.29f to 5, .34f to 8, .42f to 4, .46f to 6, .54f to 9, .6f to 5, .73f to 7, .79f to 4)
                .forEach { (x, height) -> p.rect((w * x).toInt(), h - 3 - height, 5, height, town) }
            for (x in 0 until w) { p.rect(x, h - 3, 1, 1, ground); p.rect(x, h - 2, 1, 2, if ((x / 4) % 2 == 1) ground else groundDark) }
        }
    }
    return p.bitmap
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

/** Six tiles: the five pixel scenes and the album. The chosen one gets a teal ring and a small square mark. */
@Composable
internal fun BannerPicker(repository: SocialRepository?, choice: String, imagePath: String?, pending: ImageBitmap?, uploading: Boolean,
    onChoose: (String) -> Unit, onAlbum: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tiles = BANNER_CHOICES + (BANNER_PHOTO to if (uploading) "处理中…" else "相册")
    Column(Modifier.fillMaxWidth().testTag("banner-picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, label) ->
                    val selected = key == choice
                    val hasImage = key == BANNER_PHOTO && (pending != null || imagePath != null)
                    val shape = RoundedCornerShape(10.dp)
                    Surface(onClick = { if (key == BANNER_PHOTO && !hasImage) onAlbum() else if (key == BANNER_PHOTO && selected) onAlbum() else onChoose(key) },
                        enabled = !(key == BANNER_PHOTO && (repository == null || uploading)), shape = shape,
                        color = if (key == BANNER_PHOTO && !hasImage) scheme.surface else scheme.surfaceVariant,
                        modifier = Modifier.weight(1f).height(58.dp)
                            .then(if (selected) Modifier.border(2.5.dp, scheme.primary, shape) else if (key == BANNER_PHOTO && !hasImage) Modifier.border(1.5.dp, scheme.outline, shape) else Modifier)
                            .semantics { contentDescription = "主页背景：$label"; this.selected = selected; role = Role.RadioButton }
                            .testTag("banner-${key.ifEmpty { "sky" }}")) {
                        Box(Modifier.fillMaxSize().clip(shape)) {
                            if (key == BANNER_PHOTO && !hasImage) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                LineIcon(Symbol.IMAGE, Modifier.size(15.dp), scheme.onSurfaceVariant)
                                Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
                            } else {
                                ProfileBannerArt(repository, if (key == BANNER_PHOTO) imagePath else key.ifEmpty { null }, Modifier.fillMaxSize(),
                                    pending = if (key == BANNER_PHOTO) pending else null)
                                Text(label, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 3.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold,
                                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .5f), blurRadius = 4f)), color = Color.White)
                            }
                            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(12.dp).background(scheme.surface).padding(3.dp).background(scheme.primary))
                        }
                    }
                }
            }
        }
    }
}

/** Width : height of the profile banner on a typical phone card (328 × 104 dp); the crop frame uses the same shape. */
internal const val BANNER_RATIO = 3.15f

/** Cuts [area] (source pixels) out of a picked photo and re-encodes it at most 1200 px wide. */
internal fun bannerCrop(source: ByteArray, area: androidx.compose.ui.geometry.Rect): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size) ?: error("无法读取这张图片")
    try {
        val left = area.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val top = area.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val width = area.width.roundToInt().coerceIn(1, bitmap.width - left)
        val height = area.height.roundToInt().coerceIn(1, bitmap.height - top)
        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        val scaled = if (width > 1200) Bitmap.createScaledBitmap(cropped, 1200, (1200f * height / width).roundToInt().coerceAtLeast(1), true) else cropped
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
        if (scaled !== cropped) scaled.recycle()
        if (cropped !== bitmap) cropped.recycle()
        return output.toByteArray()
    } finally { bitmap.recycle() }
}

/**
 * Full-screen framing for a banner photo: a fixed [BANNER_RATIO] frame in the middle, the photo dragged and pinched
 * beneath it. The photo always covers the frame. [onConfirm] receives the framed area in source pixels.
 */
@Composable
internal fun BannerCropper(source: ImageBitmap, onCancel: () -> Unit, onConfirm: (androidx.compose.ui.geometry.Rect) -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xF2080B10)).systemBarsPadding().clipToBounds().testTag("banner-cropper")) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val areaW = constraints.maxWidth.toFloat(); val areaH = constraints.maxHeight.toFloat()
            val frameW = areaW - with(density) { 32.dp.toPx() }
            val frameH = frameW / BANNER_RATIO
            val frame = Offset((areaW - frameW) / 2, (areaH - frameH) / 2)
            val iw = source.width.toFloat(); val ih = source.height.toFloat()
            val minScale = maxOf(frameW / iw, frameH / ih)
            var scale by remember(source, frameW) { mutableFloatStateOf(minScale) }
            var offset by remember(source, frameW) { mutableStateOf(Offset((frameW - iw * minScale) / 2, (frameH - ih * minScale) / 2)) }
            fun clamp(o: Offset, s: Float) = Offset(o.x.coerceIn(frameW - iw * s, 0f), o.y.coerceIn(frameH - ih * s, 0f))
            Canvas(Modifier.fillMaxSize().pointerInput(source, frameW) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(minScale, minScale * 6)
                    val c = centroid - frame
                    offset = clamp((offset - c) * (next / scale) + c + pan, next)
                    scale = next
                }
            }) {
                drawImage(source, IntOffset.Zero, IntSize(source.width, source.height),
                    dstOffset = IntOffset((frame.x + offset.x).roundToInt(), (frame.y + offset.y).roundToInt()),
                    dstSize = IntSize((iw * scale).roundToInt(), (ih * scale).roundToInt()), filterQuality = FilterQuality.Medium)
                val shade = Color.Black.copy(alpha = .6f)
                drawRect(shade, Offset.Zero, Size(size.width, frame.y))
                drawRect(shade, Offset(0f, frame.y + frameH), Size(size.width, size.height - frame.y - frameH))
                drawRect(shade, Offset(0f, frame.y), Size(frame.x, frameH))
                drawRect(shade, Offset(frame.x + frameW, frame.y), Size(size.width - frame.x - frameW, frameH))
                drawRect(Color.White, frame, Size(frameW, frameH), style = Stroke(2.dp.toPx()))
                // Pixel corner ticks mark the frame as the thing to aim with.
                val t = 4.dp.toPx(); val l = 16.dp.toPx()
                listOf(frame, Offset(frame.x + frameW, frame.y), Offset(frame.x, frame.y + frameH), Offset(frame.x + frameW, frame.y + frameH)).forEach { p ->
                    val right = p.x > frame.x; val bottom = p.y > frame.y
                    drawRect(Color.White, Offset(if (right) p.x - l else p.x, if (bottom) p.y - t else p.y), Size(l, t))
                    drawRect(Color.White, Offset(if (right) p.x - t else p.x, if (bottom) p.y - l else p.y), Size(t, l))
                }
            }
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PixelOutlineKey(onClick = onCancel, minHeight = 36.dp) { Text("取消", maxLines = 1) }
                Text("选择背景区域", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
                PixelKey(onClick = {
                    onConfirm(androidx.compose.ui.geometry.Rect(-offset.x / scale, -offset.y / scale, (frameW - offset.x) / scale, (frameH - offset.y) / scale))
                }, minHeight = 36.dp, modifier = Modifier.testTag("banner-crop-use")) { Text("使用", maxLines = 1) }
            }
        }
    }
}
