package dev.lzk.fitlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * A person at the top of their page: a large ringed avatar beside a large nickname, a quieter bio and the FitLog ID
 * bar. The avatar only opens for viewing; on one's own page the nickname and bio edit in place.
 */
@Composable
internal fun ProfileHero(repository: SocialRepository?, profile: SocialProfile, editable: Boolean,
    status: String? = null, onNickname: (String) -> Unit = {}, onBio: (String) -> Unit = {},
    action: (@Composable () -> Unit)? = null, extra: @Composable ColumnScope.() -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    var viewing by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape)
        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp).testTag("profile-hero"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(92.dp).border(2.dp, scheme.primary, CircleShape).padding(4.dp)) {
                SocialAvatar(repository, profile, size = 84.dp, modifier = Modifier.testTag("profile-avatar")) { viewing = true }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (editing == "nickname") InlineEditor(profile.nickname, 40, singleLine = true, label = "昵称", tag = "edit-nickname",
                    onCancel = { editing = null }) { onNickname(it); editing = null }
                else Text(profile.nickname, Modifier.fillMaxWidth().then(if (editable) Modifier.clickable { editing = "nickname" } else Modifier)
                    .testTag("profile-nickname"), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.let { StatusTag(it) }
            }
        }
        if (editing == "bio") InlineEditor(profile.bio, 300, singleLine = false, label = "简介", tag = "edit-bio",
            onCancel = { editing = null }) { onBio(it); editing = null }
        else if (profile.bio.isNotBlank() || editable) Text(profile.bio.ifBlank { "写一句简介" },
            Modifier.fillMaxWidth().then(if (editable) Modifier.clickable { editing = "bio" } else Modifier).testTag("profile-bio"),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = if (profile.bio.isBlank()) .7f else 1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        FitLogIdBar(profile.fitlogId)
        extra()
        action?.invoke()
    }
    if (viewing) AvatarViewer(repository, profile) { viewing = false }
}

/** Small square-cut presence tag with a dot. */
@Composable
internal fun StatusTag(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.background(scheme.primaryContainer, PixelShapeSmall).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).background(scheme.primary))
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = scheme.primary, maxLines = 1)
    }
}

/** The FitLog ID in pixel digits on a tonal bar, with a copy key that flips to “已复制” for a moment. */
@Composable
internal fun FitLogIdBar(id: String) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).background(scheme.surfaceVariant, PixelShapeSmall).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("FitLog ID", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
        Text(id, Modifier.weight(1f).testTag("profile-fitlog-id"), style = pixelNumber(20.sp), color = scheme.onSurface, maxLines = 1)
        val label = if (copied) "已复制" else "复制"
        val copy: () -> Unit = {
            scope.launch {
                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("FitLog ID", id)))
                copied = true; delay(1500); copied = false
            }
        }
        if (copied) PixelKey(onClick = copy, minHeight = 28.dp, depth = 2.dp, modifier = Modifier.semanticsLabel("复制 FitLog ID")) {
            LineIcon(Symbol.CHECK, Modifier.size(11.dp), LocalContentColor.current); Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        } else PixelOutlineKey(onClick = copy, minHeight = 28.dp, edge = scheme.primary, contentColor = scheme.primary,
            contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.semanticsLabel("复制 FitLog ID")) {
            LineIcon(Symbol.EXPORT, Modifier.size(11.dp), LocalContentColor.current); Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** A field that replaces a line of text in place, with a teal check to save and a plain key to cancel. */
@Composable
internal fun InlineEditor(initial: String, limit: Int, singleLine: Boolean, label: String, tag: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable(tag) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, { value = it.take(limit) }, Modifier.weight(1f).focusRequester(focus).testTag(tag), singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4, placeholder = { Text(label) },
            textStyle = if (singleLine) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onSave(value.trim()) }))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelKey(onClick = { onSave(value.trim()) }, minHeight = 34.dp, minWidth = 36.dp, depth = 3.dp, modifier = Modifier.testTag("$tag-save").semanticsLabel("保存$label")) {
                LineIcon(Symbol.CHECK, Modifier.size(13.dp), LocalContentColor.current)
            }
            PixelOutlineKey(onClick = onCancel, minHeight = 34.dp, minWidth = 36.dp, contentPadding = PaddingValues(0.dp), modifier = Modifier.semanticsLabel("取消")) {
                LineIcon(Symbol.CLOSE, Modifier.size(11.dp), LocalContentColor.current)
            }
        }
    }
}

/** The avatar at full size on a dark ground; any tap closes it. */
@Composable
internal fun AvatarViewer(repository: SocialRepository?, profile: SocialProfile, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xEB06090D)).clickable(onClick = onClose).testTag("avatar-viewer"), contentAlignment = Alignment.Center) {
            val path = profile.avatarPath
            if (repository != null && path != null) SocialImage(repository, path, Modifier.fillMaxWidth().aspectRatio(1f).padding(24.dp).clip(CircleShape),
                previewEnabled = false, fullSize = true)
            else Box(Modifier.size(240.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(profile.nickname.take(1), style = MaterialTheme.typography.displayLarge)
            }
        }
    }
}

/** The private note on a friend's page: a dashed row that edits in place. */
@Composable
internal fun NoteRow(note: String, onSave: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) { InlineEditor(note, 40, singleLine = true, label = "备注", tag = "edit-note", onCancel = { editing = false }) { onSave(it); editing = false }; return }
    val outline = scheme.outline
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = true }.drawBehind {
            drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("friend-note"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("备注", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.onSurfaceVariant)
        Text(note.ifBlank { "添加备注" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (note.isBlank()) FontWeight.Normal else FontWeight.Bold),
            color = if (note.isBlank()) scheme.onSurfaceVariant else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LineIcon(Symbol.EDIT, Modifier.size(12.dp), scheme.onSurfaceVariant)
    }
}

/** What a friend is doing right now: exercise, the current set and a cell per set. */
@Composable
internal fun LiveTrainingCard(live: LiveTraining, status: PresenceStatus) {
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(live.restEndsAt) { while (isActive) { now = Instant.now(); delay(1_000) } }
    Column(Modifier.fillMaxWidth().background(scheme.primaryContainer.copy(alpha = .6f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
        .testTag("live-training"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LineIcon(Symbol.DUMBBELL, Modifier.size(13.dp), scheme.primary)
            Text(live.label + " · " + live.exercise, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (live.recordMetric == RecordMetric.SECONDS) { Text("${live.reps}", style = pixelNumber(22.sp)); Text("秒", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelSmall) }
            else {
                Text(WeightUnits.format(live.weightGrams, WeightUnit.KG), style = pixelNumber(22.sp)); Text("kg ×", Modifier.padding(bottom = 2.dp), style = MaterialTheme.typography.labelSmall)
                Text("${live.reps}", style = pixelNumber(22.sp))
            }
            Spacer(Modifier.weight(1f))
            if (status == PresenceStatus.REST) {
                val remaining = live.restEndsAt?.epochSecond?.minus(now.epochSecond)?.coerceAtLeast(0) ?: 0
                Text("休息 %02d:%02d".format(remaining / 60, remaining % 60), style = pixelNumber(18.sp), color = scheme.primary)
            } else Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                repeat(live.total.coerceIn(0, 12)) { i ->
                    Box(Modifier.size(10.dp).then(if (i < live.done) Modifier.background(scheme.primary) else Modifier.border(1.5.dp, scheme.outline)))
                }
            }
        }
    }
}

/** An entry row on profile pages: icon tile, title, chevron. */
@Composable
internal fun ProfileEntries(content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape), content = content)
}

/** Three pixel figures under the profile: year days, sessions and friends. Read only. */
@Composable
internal fun ProfileFigures(figures: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().testTag("profile-figures"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        figures.forEach { (value, label) -> StatTile(value, label, Modifier.weight(1f)) }
    }
}
