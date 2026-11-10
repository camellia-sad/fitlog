package dev.lzk.fitlog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.ImageBitmap
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
import java.time.LocalDate

/**
 * A person's member card: a pixel banner (sky, scene or their own image) with the FITLOG mark, an optional puppy that
 * matches a real state and a [corner] control; a large ringed avatar overlapping the banner's lower edge with the
 * nickname beside it, below the banner; a quieter bio; then a perforated stub holding the FitLog ID. The avatar only
 * opens for viewing; on one's own page the nickname and bio edit in place.
 */
@Composable
internal fun ProfileHero(repository: SocialRepository?, profile: SocialProfile, editable: Boolean,
    onNickname: (String) -> Unit = {}, onBio: (String) -> Unit = {}, corner: (@Composable () -> Unit)? = null,
    puppy: PuppyPose? = null, streakWeeks: Int = 0, pendingBanner: ImageBitmap? = null, tag: String = "profile-hero",
    extra: @Composable ColumnScope.() -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    var viewing by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape).testTag(tag)) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(BANNER_HEIGHT).testTag("profile-banner")) {
                ProfileBannerArt(repository, profile.banner, Modifier.fillMaxSize(), pending = pendingBanner)
                Text("FITLOG", Modifier.padding(start = 12.dp, top = 8.dp), style = pixelNumber(17.sp).copy(letterSpacing = 1.4.sp,
                    shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .2f), androidx.compose.ui.geometry.Offset(2f, 2f))),
                    color = Color.White.copy(alpha = .88f))
                puppy?.let { PixelPuppy(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp).size(52.dp).testTag("profile-puppy"), it) }
                corner?.let { Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { it() } }
            }
            // The avatar overlaps the banner's lower edge; the nickname starts below the banner so the sky never covers it.
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = BANNER_HEIGHT - AVATAR_OVERLAP), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(98.dp).background(scheme.surface, CircleShape).padding(3.dp).border(2.5.dp, scheme.primary, CircleShape).padding(4.dp)) {
                    SocialAvatar(repository, profile, size = 82.dp, modifier = Modifier.testTag("profile-avatar")) { viewing = true }
                }
                Column(Modifier.weight(1f).padding(top = AVATAR_OVERLAP + 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (editing == "nickname") InlineEditor(profile.nickname, 40, singleLine = true, label = "昵称", tag = "edit-nickname",
                        onCancel = { editing = null }) { onNickname(it); editing = null }
                    else Text(profile.nickname, Modifier.fillMaxWidth().then(if (editable) Modifier.clickable { editing = "nickname" } else Modifier)
                        .testTag("profile-nickname"), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                        color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (streakWeeks >= 2) StreakTag(streakWeeks)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing == "bio") InlineEditor(profile.bio, 300, singleLine = false, label = "简介", tag = "edit-bio",
                onCancel = { editing = null }) { onBio(it); editing = null }
            else if (profile.bio.isNotBlank() || editable) Text(profile.bio.ifBlank { "写一句简介" },
                Modifier.fillMaxWidth().then(if (editable) Modifier.clickable { editing = "bio" } else Modifier).testTag("profile-bio"),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant.copy(alpha = if (profile.bio.isBlank()) .7f else 1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            extra()
        }
        Perforation(Modifier.padding(top = 12.dp))
        FitLogIdLine(profile.fitlogId, Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp, top = 2.dp))
    }
    if (viewing) AvatarViewer(repository, profile) { viewing = false }
}

private val BANNER_HEIGHT = 104.dp
private val AVATAR_OVERLAP = 40.dp

/** Achievement-orange tag for consecutive training weeks. */
@Composable
private fun StreakTag(weeks: Int) {
    val fit = fitColors
    Row(Modifier.background(fit.cream, PixelShapeSmall).padding(horizontal = 7.dp, vertical = 1.dp).testTag("profile-streak"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LineIcon(Symbol.MEDAL, Modifier.size(11.dp), fit.orangeDeep)
        Text("连续", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = fit.orangeDeep)
        Text("$weeks", style = pixelNumber(17.sp), color = fit.orangeDeep)
        Text("周", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = fit.orangeDeep)
    }
}

/** A dashed tear line with a half-round notch punched from each edge of the card. */
@Composable
private fun Perforation(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outline
    val hole = MaterialTheme.colorScheme.background
    val rim = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(20.dp)) {
        val r = 9.dp.toPx(); val y = size.height / 2
        drawLine(line, androidx.compose.ui.geometry.Offset(16.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), y),
            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        listOf(0f, size.width).forEach { x ->
            drawCircle(hole, r, androidx.compose.ui.geometry.Offset(x, y))
            drawCircle(rim, r, androidx.compose.ui.geometry.Offset(x, y), style = Stroke(1.dp.toPx()))
        }
    }
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

/** “FitLog ID” in bold, the ID itself in plain type a step quieter, and a copy key that flips to “已复制” for a moment. */
@Composable
internal fun FitLogIdLine(id: String, modifier: Modifier = Modifier, label: String = "FitLog ID") {
    val scheme = MaterialTheme.colorScheme
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(id) { mutableStateOf(false) }
    // The label and copy key share the first line so the full 32-character ID gets the second line to itself.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black), color = scheme.onSurface, maxLines = 1)
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
    Text(id, Modifier.fillMaxWidth().testTag("profile-fitlog-id"), style = MaterialTheme.typography.bodyMedium.copy(letterSpacing = .6.sp),
        color = scheme.onSurfaceVariant, maxLines = 2)
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

/** This year at a glance on one's own page; every part is read only. */
data class ProfileActivity(
    /** Completed sets per day, for the last [ProfileActivity.WEEKS] weeks. */
    val sets: Map<LocalDate, Int> = emptyMap(),
    /** This year's completed sets per muscle group, as shares. */
    val parts: List<Pair<MuscleGroup, Float>> = emptyList(),
    val streakWeeks: Int = 0,
    val trainedToday: Boolean = false,
    val monthSessions: Int = 0,
) { companion object { const val WEEKS = 16 } }

/** Builds the panel from recorded sessions; only completed sets count, as everywhere else in history. */
internal fun profileActivity(workouts: List<Workout>, catalog: List<Exercise>, today: LocalDate): ProfileActivity {
    val days = TrainingHistory.days(workouts)
    val gridStart = today.with(java.time.DayOfWeek.MONDAY).minusWeeks(ProfileActivity.WEEKS - 1L)
    val recorded = workouts.filter { it.isRecordedTraining() && it.completedSetCount > 0 }
    val groups = LinkedHashMap<MuscleGroup, Int>()
    recorded.filter { !it.session.localWorkoutDate.isBefore(today.withDayOfYear(1)) }.forEach { workout ->
        workout.exercises.forEach { exercise ->
            val done = exercise.sets.count { it.status == SetStatus.COMPLETED }
            if (done > 0) catalog.find { it.id == exercise.exerciseId }?.primaryMuscles?.firstOrNull()?.group?.let { groups[it] = (groups[it] ?: 0) + done }
        }
    }
    val total = groups.values.sum().toFloat()
    return ProfileActivity(
        sets = days.filter { !it.date.isBefore(gridStart) }.associate { it.date to it.completedSets },
        parts = if (total > 0) groups.entries.sortedByDescending { it.value }.map { it.key to it.value / total } else emptyList(),
        streakWeeks = TrainingHistory.weekStreak(workouts, today),
        trainedToday = days.any { it.date == today },
        monthSessions = recorded.count { !it.session.localWorkoutDate.isBefore(today.withDayOfMonth(1)) },
    )
}

/** Three pixel figures in one row, the last 16 weeks as a day grid (today outlined orange) and this year's muscle mix. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YearPanel(figures: List<Pair<String, String>>, activity: ProfileActivity, today: LocalDate = LocalDate.now()) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape)
        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp).testTag("profile-figures"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            figures.forEachIndexed { i, (value, label) ->
                if (i > 0) Box(Modifier.padding(end = 10.dp).width(1.5.dp).fillMaxHeight().background(scheme.outlineVariant))
                Column(Modifier.weight(1f)) {
                    Text(value, style = pixelNumber(34.sp), color = scheme.onSurface, maxLines = 1)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("近 ${ProfileActivity.WEEKS} 周", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            val start = today.with(java.time.DayOfWeek.MONDAY).minusWeeks(ProfileActivity.WEEKS - 1L)
            val orange = fitColors.orange
            Row(Modifier.fillMaxWidth().testTag("profile-heat"), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(ProfileActivity.WEEKS) { week ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(7) { day ->
                            val date = start.plusWeeks(week.toLong()).plusDays(day.toLong())
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).then(if (date.isAfter(today)) Modifier else Modifier.background(trainingColor(activity.sets[date] ?: 0)))
                                .then(if (date == today) Modifier.border(1.5.dp, orange) else Modifier))
                        }
                    }
                }
            }
        }
        if (activity.parts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(10.dp).testTag("profile-parts"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                activity.parts.forEach { (group, share) -> Box(Modifier.weight(share.coerceAtLeast(.001f)).fillMaxHeight().background(group.color())) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                activity.parts.forEach { (group, _) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).background(group.color()))
                        Text(group.label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
