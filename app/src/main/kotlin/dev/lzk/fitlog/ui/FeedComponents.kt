package dev.lzk.fitlog.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/* ---------------- the like dumbbell ---------------- */

/** 16 × 12 pixel dumbbell: outer and inner plates, collars, end caps and a knurled bar. `#` is metal, `k` knurling. */
private val DUMBBELL = listOf(
    "................",
    ".##..........##.",
    ".###........###.",
    ".###........###.",
    "####.#....#.####",
    "#####kkkkkk#####",
    "#####kkkkkk#####",
    "####.#....#.####",
    ".###........###.",
    ".###........###.",
    ".##..........##.",
    "................",
)
private val DUMBBELL_CELLS = DUMBBELL.flatMapIndexed { y, row -> row.mapIndexedNotNull { x, c -> if (c != '.') Triple(x, y, c) else null } }
private val DUMBBELL_SET = DUMBBELL_CELLS.map { it.first to it.second }.toSet()

/**
 * The like mark. Unliked it is a pixel outline; liked it is solid in [tint] with a lit upper edge, a shaded lower
 * edge and a darker knurled bar, so it reads as a heavy metal dumbbell rather than a flat icon.
 */
@Composable
internal fun PixelDumbbell(liked: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val u = minOf(size.width / 16f, size.height / 12f)
        val ox = (size.width - u * 16) / 2; val oy = (size.height - u * 12) / 2
        fun cell(x: Int, y: Int, c: Color) = drawRect(c, Offset(ox + x * u, oy + y * u), Size(u + .5f, u + .5f))
        if (!liked) {
            // Only the silhouette's edge cells, so the empty state stays light.
            DUMBBELL_CELLS.forEach { (x, y, _) ->
                val edge = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1).any { it !in DUMBBELL_SET }
                if (edge) cell(x, y, outline)
            }
            return@Canvas
        }
        val light = lerp(tint, Color.White, .42f); val shade = lerp(tint, Color.Black, .28f); val deep = lerp(tint, Color.Black, .45f)
        DUMBBELL_CELLS.forEach { (x, y, c) ->
            val above = (x to y - 1) in DUMBBELL_SET; val below = (x to y + 1) in DUMBBELL_SET
            val color = when {
                c == 'k' -> if ((x + y) % 2 == 0) deep else shade
                !above -> light
                !below -> shade
                else -> tint
            }
            cell(x, y, color)
        }
        // A single glint on each outer plate.
        cell(2, 2, Color.White.copy(alpha = .85f)); cell(13, 2, Color.White.copy(alpha = .55f))
    }
}

/** One of FitLog's colours per post, chosen from its id so a post keeps its colour. */
@Composable
internal fun likeTint(postId: String): Color {
    val fit = fitColors
    val choices = listOf(MaterialTheme.colorScheme.primary, fit.orange, fit.chest, fit.back, fit.legs, fit.shoulders, fit.arms, fit.core)
    return choices[abs(postId.hashCode() % choices.size)]
}

/** Draws [progress] (0→1) of a burst of square sparks around the centre, fading as they fly out. */
private fun Modifier.sparks(progress: Float, tint: Color, reach: Dp, count: Int = 10) = drawBehind {
    if (progress <= 0f || progress >= 1f) return@drawBehind
    val centre = Offset(size.width / 2, size.height / 2)
    val distance = reach.toPx() * progress + 6.dp.toPx()
    val side = (4.dp.toPx() * (1f - progress * .5f))
    repeat(count) { i ->
        val angle = 2 * PI * i / count + (if (i % 2 == 0) 0.0 else .25)
        val d = distance * (if (i % 2 == 0) 1f else .72f)
        val p = centre + Offset((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat())
        drawRect(if (i % 3 == 0) lerp(tint, Color.White, .5f) else tint, Offset(p.x - side / 2, p.y - side / 2), Size(side, side), alpha = 1f - progress)
    }
}

/**
 * Like key: the dumbbell and its count. Liking lifts the dumbbell in steps (up, overshoot, settle) with a burst of
 * sparks; the count rolls. Unliking just drops it back to the outline.
 */
@Composable
internal fun LikeButton(post: SocialPost, enabled: Boolean, burst: Int, onToggle: () -> Unit) {
    val tint = likeTint(post.id)
    val lift = remember { Animatable(0f) }
    // Every like, from the key or a double tap ([burst]), replays the lift.
    LaunchedEffect(burst) { if (burst > 0) { lift.snapTo(0f); lift.animateTo(1f, tween(620, easing = StepEasing(8))) } }
    val p = lift.value
    val wave = sin(PI * p).toFloat()
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onToggle).semantics { contentDescription = if (post.liked) "取消点赞" else "点赞"; selected = post.liked }
        .heightIn(min = 40.dp).padding(horizontal = 10.dp).testTag("like-${post.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PixelDumbbell(post.liked, tint, Modifier.size(width = 28.dp, height = 21.dp).sparks(p, tint, 22.dp)
            .graphicsLayer { scaleX = 1f + .5f * wave; scaleY = 1f + .5f * wave; translationY = -9.dp.toPx() * wave; rotationZ = -14f * sin(2 * PI * p).toFloat() })
        val roll = if (p in 0.01f..0.5f) (1f - p * 2) else 0f
        Text("${post.likeCount}", Modifier.graphicsLayer { translationY = 10.dp.toPx() * roll; alpha = 1f - roll * .8f },
            style = pixelNumber(19.sp), color = if (post.liked) tint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The large dumbbell that pops over a post's content on a double tap. */
@Composable
private fun BigLike(trigger: Int, tint: Color, modifier: Modifier) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(trigger) { if (trigger > 0) { pop.snapTo(0f); pop.animateTo(1f, tween(760, easing = StepEasing(9))) } }
    val p = pop.value
    if (p >= 1f) return
    val scale = if (p < .25f) .4f + p / .25f * .8f else 1.2f - (p - .25f) * .3f
    Box(modifier.size(110.dp).sparks(p, tint, 46.dp, 14), contentAlignment = Alignment.Center) {
        PixelDumbbell(true, tint, Modifier.size(width = 84.dp, height = 63.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = -24.dp.toPx() * p; alpha = if (p > .7f) (1f - p) / .3f else 1f })
    }
}

/* ---------------- post card ---------------- */

/**
 * A post: author and time (no ID), an overflow menu, the text, a training card or images, and the like and
 * comment keys. A double tap on the text or card likes it.
 */
@Composable
internal fun PostCard(repository: SocialRepository, post: SocialPost, me: SocialProfile, enabled: Boolean, onPerson: (String) -> Unit,
    onLike: (() -> Unit)?, onComments: (() -> Unit)?, onDelete: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tint = likeTint(post.id)
    var burst by remember(post.id) { mutableIntStateOf(0) }
    var big by remember(post.id) { mutableIntStateOf(0) }
    val like = onLike?.let { toggle -> { if (!post.liked) burst++; toggle() } }
    Surface(modifier.fillMaxWidth().testTag("post-${post.id}"), shape = RoundedCornerShape(22.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Box {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = if (onLike != null) 4.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PostHeader(repository, post, me, onPerson, onDelete)
                // A training post shows what its author wrote above the card; the body is the text version for older apps.
                val caption = post.workout?.caption ?: post.body
                Column(Modifier.padding(end = 4.dp).pointerInput(post.id, post.liked, enabled) {
                    detectTapGestures(onDoubleTap = { if (enabled && onLike != null) { big++; if (!post.liked) { burst++; onLike() } } })
                }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp))
                    post.workout?.let { WorkoutCard(it) }
                }
                if (post.mediaPaths.isNotEmpty()) PostImages(repository, post.mediaPaths, Modifier.padding(end = 4.dp))
                if (onLike != null && like != null) {
                    HorizontalDivider(Modifier.padding(end = 4.dp), color = scheme.outlineVariant)
                    Row(Modifier.offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
                        LikeButton(post, enabled, burst, like)
                        onComments?.let { open ->
                            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = open).semantics { contentDescription = "查看评论" }
                                .heightIn(min = 40.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ActivityGlyph("comment", modifier = Modifier.size(22.dp))
                                Text("${post.commentCount}", style = pixelNumber(19.sp), color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            BigLike(big, tint, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PostHeader(repository: SocialRepository, post: SocialPost, me: SocialProfile, onPerson: (String) -> Unit, onDelete: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SocialAvatar(repository, SocialProfile(post.ownerId, post.fitlogId, post.nickname, avatarPath = post.avatarPath), size = 40.dp) { onPerson(post.ownerId) }
        Column(Modifier.weight(1f).clickable { onPerson(post.ownerId) }) {
            Text(post.nickname, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, fontSize = 15.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ChatTimes.label(post.createdAt), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = .75f), maxLines = 1)
        }
        Box {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { menu = true }.semantics { contentDescription = "更多操作" }
                .testTag("post-more-${post.id}"), contentAlignment = Alignment.Center) {
                LineIcon(Symbol.MORE, Modifier.size(14.dp), scheme.onSurfaceVariant)
            }
            DropdownMenu(menu, { menu = false }) {
                if (post.ownerId != me.id) DropdownMenuItem(text = { Text("查看主页") }, onClick = { menu = false; onPerson(post.ownerId) },
                    leadingIcon = { LineIcon(Symbol.PERSON, Modifier.size(14.dp), scheme.onSurfaceVariant) })
                onDelete?.let { delete ->
                    DropdownMenuItem(text = { Text("删除动态", color = scheme.error) }, onClick = { menu = false; delete() },
                        leadingIcon = { LineIcon(Symbol.TRASH, Modifier.size(14.dp), scheme.error) })
                }            }
        }
    }
}

/** Images as one rounded block: one full width, more in rows of three. */
@Composable
internal fun PostImages(repository: SocialRepository, paths: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (paths.size == 1) SocialImage(repository, paths.single(), Modifier.fillMaxWidth().height(220.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else paths.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { path -> SocialImage(repository, path, Modifier.weight(1f).aspectRatio(1f), gallery = paths,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Three teal squares: they follow the pull, then hop in turn while refreshing or loading. */
@Composable
internal fun PixelRefresh(active: Boolean, fraction: Float, modifier: Modifier = Modifier) {
    if (!active) return
    val scheme = MaterialTheme.colorScheme
    val hop = androidx.compose.animation.core.rememberInfiniteTransition(label = "刷新")
    val phase by hop.animateFloat(0f, 3f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = StepEasing(6))), label = "跳动")
    Row(modifier.padding(top = (8 + 16 * fraction.coerceIn(0f, 1f)).dp).graphicsLayer { alpha = fraction.coerceIn(.35f, 1f) }
        .testTag("feed-refresh"), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val up = if (fraction >= 1f || fraction == 0f) (if (phase.toInt() == i) 1f else 0f) else 0f
            Box(Modifier.size(7.dp).graphicsLayer { translationY = -6.dp.toPx() * up }.background(scheme.primary))
        }
    }
}

/** Writing a post: a sheet with cancel and publish keys, a large text area and a three-column image grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostComposer(repository: SocialRepository, images: List<String>, body: String, busy: Boolean, error: String?,
    uploading: Int = 0, onBody: (String) -> Unit, onPick: () -> Unit, onRemove: (String) -> Unit, onCancel: () -> Unit, onPublish: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = { if (!busy) onCancel() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp).testTag("post-composer"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelOutlineKey(onClick = onCancel, enabled = !busy, minHeight = 36.dp) { Text("取消", maxLines = 1) }
                Text("发动态", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                PixelKey(onClick = onPublish, enabled = !busy && (body.isNotBlank() || images.isNotEmpty()), minHeight = 36.dp,
                    modifier = Modifier.testTag("publish-post")) { Text(if (busy && uploading == 0) "发表中…" else "发表", maxLines = 1) }
            }
            OutlinedTextField(body, onBody, Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text("今天有什么新进步？") },
                maxLines = 8, enabled = !busy, shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyLarge)
            val cells = images.map { it as String? } + (if (images.size < SocialRules.MAX_IMAGES) listOf(null) else emptyList())
            cells.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { path ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
                            if (path != null) {
                                SocialImage(repository, path, Modifier.fillMaxSize(), gallery = images, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(26.dp).background(Color.Black.copy(alpha = .6f), PixelShapeSmall)
                                    .clickable(enabled = !busy) { onRemove(path) }.semantics { contentDescription = "移除第 ${images.indexOf(path) + 1} 张图片" },
                                    contentAlignment = Alignment.Center) { LineIcon(Symbol.CLOSE, Modifier.size(10.dp), Color.White) }
                            } else Column(Modifier.fillMaxSize().drawBehind {
                                drawRoundRect(scheme.outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(),
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                            }.clickable(enabled = !busy, onClick = onPick).testTag("add-post-image"), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                // While picked pictures upload, the tile counts down what is left.
                                if (uploading > 0) PixelRefresh(true, 1f) else LineIcon(Symbol.IMAGE, Modifier.size(18.dp), scheme.onSurfaceVariant)
                                Text(if (uploading > 0) "还剩 $uploading 张" else "添加图片", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                                Text("${images.size}/${SocialRules.MAX_IMAGES}", style = pixelNumber(15.sp), color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/* ---------------- training card ---------------- */

/**
 * A shared workout: a strip tinted by the main muscle with the label stamp, start time and status; four pixel
 * figures; the muscle mix; a row per exercise with a cell per completed set and its heaviest set; new records in
 * orange; the reflection at the bottom.
 */
@Composable
internal fun WorkoutCard(card: PostWorkout, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val fit = fitColors
    val groups = card.exercises.mapNotNull { e -> e.muscle?.group?.let { it to e.sets.size } }
    val main = groups.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.maxByOrNull { it.value }?.key
    val hue = main?.color() ?: scheme.primary
    var expanded by remember(card) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape).testTag("workout-card")) {
        Row(Modifier.fillMaxWidth().background(main?.tint() ?: scheme.primaryContainer).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).background(scheme.surface, PixelShapeSmall).border(1.5.dp, hue, PixelShapeSmall), contentAlignment = Alignment.Center) {
                Text(card.stamp, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), color = hue)
            }
            Column(Modifier.weight(1f)) {
                Text(card.label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ChatTimes.label(card.startedAt) + " 开始", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            if (card.finished) Text("已完成", Modifier.background(scheme.primary, PixelShapeSmall).padding(horizontal = 7.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onPrimary, maxLines = 1)
            else Text("完成 ${card.completedSets}/${card.totalSets} 组", Modifier.background(scheme.surface, PixelShapeSmall)
                .border(1.5.dp, scheme.outline, PixelShapeSmall).padding(horizontal = 7.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold), color = scheme.onSurfaceVariant, maxLines = 1)
        }
        val figures = buildList {
            add("${card.exercises.size}" to "动作"); add("${card.completedSets}" to "组"); add("${card.minutes}" to "分钟")
            if (card.volumeGrams > 0) add(volume(card.volumeGrams, card.unit) to "训练量 ${card.unit.label}")
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            figures.forEachIndexed { i, (value, label) ->
                if (i > 0) Box(Modifier.padding(end = 8.dp).width(1.5.dp).fillMaxHeight().background(scheme.outlineVariant))
                Column(Modifier.weight(1f)) {
                    Text(value, style = pixelNumber(26.sp), color = scheme.onSurface, maxLines = 1)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        val mix = groups.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }
        if (mix.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            mix.forEach { (group, sets) -> Box(Modifier.weight(sets.toFloat()).fillMaxHeight().background(group.color())) }
        }
        val rows = if (expanded) card.exercises else card.exercises.take(PostWorkouts.VISIBLE)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
            rows.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(e.muscle?.group?.color() ?: scheme.outline))
                    if (e.previousBest != null) Box(Modifier.size(16.dp).background(fit.cream, PixelShapeSmall), contentAlignment = Alignment.Center) {
                        LineIcon(Symbol.MEDAL, Modifier.size(11.dp), fit.orangeDeep)
                    }
                    Text(e.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(e.sets.size.coerceAtMost(10)) { Box(Modifier.size(7.dp).background(scheme.primary)) }
                    }
                    Text(e.best?.let { bestText(it, e.metric, card.unit) }.orEmpty(), Modifier.widthIn(min = 70.dp),
                        style = pixelNumber(16.sp), color = scheme.onSurface, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            if (card.exercises.size > PostWorkouts.VISIBLE) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }
                .padding(vertical = 6.dp).testTag("workout-card-fold"), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "收起" else "展开全部 ${card.exercises.size} 个动作", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = scheme.primary)
            }
        }
        card.exercises.filter { it.previousBest != null }.forEach { e ->
            val best = e.best ?: return@forEach
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).background(fit.cream, PixelShapeSmall)
                .padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LineIcon(Symbol.MEDAL, Modifier.size(12.dp), fit.orangeDeep)
                Text("新纪录 · ${e.name}", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = fit.orangeDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(WeightUnits.format(best.weightGrams, card.unit), style = pixelNumber(17.sp), color = fit.orangeDeep)
                Text("${card.unit.label} · 之前 ${WeightUnits.format(requireNotNull(e.previousBest), card.unit)}", style = MaterialTheme.typography.labelSmall,
                    color = fit.orangeDeep, maxLines = 1)
            }
        }
        if (card.feeling.isNotBlank()) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .background(scheme.surfaceVariant, RoundedCornerShape(12.dp)).height(IntrinsicSize.Min).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(hue, RoundedCornerShape(2.dp)))
            Text(card.feeling, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

private fun bestText(set: PostSet, metric: RecordMetric, unit: WeightUnit) = when {
    metric == RecordMetric.SECONDS -> "${set.reps} 秒"
    set.weightGrams == 0L -> "自重 × ${set.reps}"
    else -> "${WeightUnits.format(set.weightGrams, unit)} ${unit.label} × ${set.reps}"
}

/** Total load in whole units with thousands separators; volumes exceed the single-set weight limit, so no WeightUnits here. */
private fun volume(grams: Long, unit: WeightUnit): String {
    val value = if (unit == WeightUnit.KG) grams / 1000.0 else grams / 453.59237
    return String.format(java.util.Locale.ROOT, "%,d", value.roundToLong())
}
