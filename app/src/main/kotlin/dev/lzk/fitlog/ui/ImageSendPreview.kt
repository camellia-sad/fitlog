package dev.lzk.fitlog.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lzk.fitlog.domain.SocialRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A picked picture waiting in the send preview; [id] keeps its place steady while others are removed. */
internal class PickedImage(val jpeg: ByteArray) { val id: String = UUID.randomUUID().toString() }

private val PreviewGround = Color(0xFF0D1314)
private val PreviewInk = Color.White

/**
 * Picked pictures before they go into the chat: the current one large (swipe between them), a pixel count at the top,
 * and a strip of thumbnails below to jump to, remove, or add more up to [SocialRules.MAX_IMAGES]. Nothing is sent until
 * [发送 N 张]; [取消] drops them all. A quote being answered rides with the first picture.
 */
@Composable
internal fun ImageSendPreview(images: List<PickedImage>, busy: Boolean, error: String?, quoting: String?,
    onAdd: () -> Unit, onRemove: (PickedImage) -> Unit, onCancel: () -> Unit, onSend: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val scheme = MaterialTheme.colorScheme
        val pager = rememberPagerState { images.size }
        val strip = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val current = pager.currentPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        // The strip follows the pager so the highlighted thumbnail stays in view.
        LaunchedEffect(current) { strip.animateScrollToItem(current) }
        Column(Modifier.fillMaxSize().background(PreviewGround).safeDrawingPadding().testTag("image-preview")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).background(PreviewInk.copy(alpha = .12f), PixelShapeSmall)
                    .clickable(enabled = !busy, onClick = onCancel).semantics { contentDescription = "取消" }.testTag("cancel-images"),
                    contentAlignment = Alignment.Center) { LineIcon(Symbol.CLOSE, Modifier.size(14.dp), PreviewInk) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    Text("${current + 1}", style = pixelNumber(26.sp), color = PreviewInk, modifier = Modifier.testTag("preview-index"))
                    Text(" / ${images.size}", style = pixelNumber(19.sp), color = PreviewInk.copy(alpha = .55f))
                }
                PixelKey(onClick = onSend, enabled = !busy && images.isNotEmpty(), minHeight = 40.dp, modifier = Modifier.testTag("send-images")) {
                    LineIcon(Symbol.SEND, Modifier.size(13.dp), LocalContentColor.current)
                    Text(if (busy) "发送中…" else "发送 ${images.size} 张", maxLines = 1)
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp,
                key = { images.getOrNull(it)?.id ?: it }) { page ->
                images.getOrNull(page)?.let { image ->
                    val bitmap = rememberPicked(image.jpeg, 1600)
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap, "第 ${page + 1} 张图片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else PixelRefresh(true, 1f)
                    }
                }
            }
            quoting?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineIcon(Symbol.REPLY, Modifier.size(11.dp), scheme.primary)
                    Text(it, Modifier.weight(1f), color = PreviewInk.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
            LazyRow(Modifier.fillMaxWidth().testTag("preview-strip"), state = strip,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(images, key = { _, it -> it.id }) { i, image ->
                    val chosen = i == current
                    Box(Modifier.size(62.dp)) {
                        val thumb = rememberPicked(image.jpeg, 180)
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).alpha(if (chosen) 1f else .55f)
                            .background(PreviewInk.copy(alpha = .08f))
                            .clickable { scope.launch { pager.animateScrollToPage(i) } }.testTag("preview-thumb-$i")) {
                            thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        if (chosen) Box(Modifier.fillMaxSize().border(2.dp, scheme.primary, RoundedCornerShape(10.dp)))
                        Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(Color.Black.copy(alpha = .7f), PixelShapeSmall)
                            .clickable(enabled = !busy) { onRemove(image) }.semantics { contentDescription = "移除第 ${i + 1} 张图片" },
                            contentAlignment = Alignment.Center) { LineIcon(Symbol.CLOSE, Modifier.size(8.dp), PreviewInk) }
                    }
                }
                if (images.size < SocialRules.MAX_IMAGES) item(key = "add") {
                    Column(Modifier.size(62.dp).drawBehind {
                        drawRoundRect(PreviewInk.copy(alpha = .45f), cornerRadius = CornerRadius(10.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                    }.clickable(enabled = !busy, onClick = onAdd).semantics { contentDescription = "添加图片" }.testTag("preview-add"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        LineIcon(Symbol.PLUS, Modifier.size(14.dp), PreviewInk.copy(alpha = .8f))
                        Text("${images.size}/${SocialRules.MAX_IMAGES}", style = pixelNumber(14.sp), color = PreviewInk.copy(alpha = .6f))
                    }
                }
            }
        }
    }
}

/** Decodes a picked JPEG off the main thread, scaled down by powers of two to about [longSide] pixels. */
@Composable
private fun rememberPicked(jpeg: ByteArray, longSide: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, jpeg, longSide) {
        value = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    return bitmap
}
