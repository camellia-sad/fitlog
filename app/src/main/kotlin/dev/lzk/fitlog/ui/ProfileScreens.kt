package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Someone else's page, laid out like one's own: avatar, nickname, bio and FitLog ID, read only. A friend's page adds
 * the private note (the one thing that edits here), a message key, and remove / block at the bottom.
 */
@Composable
fun PersonScreen(repository: SocialRepository, me: SocialProfile, personId: String, friend: Friend?, onBack: () -> Unit,
    onChat: (() -> Unit)?, onNote: (String) -> Unit, onRemove: () -> Unit, onBlock: () -> Unit, onPerson: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var person by remember(personId) { mutableStateOf<SocialPerson?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var timeline by rememberSaveable(personId) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(personId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try { person = repository.person(personId); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "加载失败，请重试" }
                delay(15_000)
            }
        }
    }
    val profile = person?.profile ?: friend?.profile
    if (timeline && profile != null) {
        ProfileTimelineScreen(repository, me, profile, person?.sharing?.history, ownHistory = null, onBack = { timeline = false }, onPerson = onPerson)
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        FitTopBar("个人主页", onBack, backLabel = "返回")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 24.dp).testTag("person-page"),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            if (profile == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            val current = person
            // The puppy mirrors what the friend is doing right now and stays away otherwise.
            val puppy = when (current?.status) { PresenceStatus.TRAINING -> PuppyPose.LIFT; PresenceStatus.REST -> PuppyPose.REST; else -> null }
            ProfileHero(repository, profile, editable = false, puppy = puppy,
                corner = current?.status?.label?.let { label -> { StatusTag(label) } }, extra = {
                current?.live?.let { LiveTrainingCard(it, current.status) }
            })
            if (friend != null && friend.accepted) {
                NoteRow(friend.note, onNote)
                if (onChat != null) PixelButton(onClick = onChat, modifier = Modifier.fillMaxWidth().testTag("person-chat"), minHeight = 48.dp) {
                    LineIcon(Symbol.CHAT, Modifier.size(16.dp), LocalContentColor.current); Text("发消息")
                }
            }
            ProfileEntries {
                ListRow(Symbol.LIST, "动态与训练历史", tone = RowTone.HERO, modifier = Modifier.testTag("person-timeline")) { timeline = true }
            }
            if (friend != null && friend.accepted) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                PixelOutlineKey(onClick = { confirm = "remove" }, edge = scheme.error, contentColor = scheme.error, minHeight = 34.dp,
                    modifier = Modifier.testTag("person-remove")) { Text("删除好友", maxLines = 1) }
                PixelOutlineKey(onClick = { confirm = "block" }, edge = scheme.error, contentColor = scheme.error, minHeight = 34.dp,
                    modifier = Modifier.testTag("person-block")) { Text("拉黑", maxLines = 1) }
            }
        }
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (action == "block") "拉黑此好友？" else "删除好友？") },
            text = { Text("好友关系将移除，停止后续共享与新消息。") },
            confirmButton = { TextButton(onClick = { confirm = null; if (action == "block") onBlock() else onRemove() }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}

/**
 * “动态与训练历史”: whose page it is (small avatar and nickname), then a pixel switch between the training history and
 * the posts. One's own history is the history page itself; a friend's is what they share.
 */
@Composable
fun ProfileTimelineScreen(repository: SocialRepository, me: SocialProfile, owner: SocialProfile, sharingHistory: Boolean?,
    ownHistory: (@Composable () -> Unit)?, onBack: () -> Unit, onPerson: (String) -> Unit) {
    var tab by rememberSaveable(owner.id) { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().testTag("profile-timeline")) {
        FitTopBar("动态与训练历史", onBack, backLabel = "返回")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SocialAvatar(repository, owner, size = 40.dp) {}
            Column(Modifier.weight(1f)) {
                Text(owner.nickname, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(owner.fitlogId, style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PixelSegmented(listOf("训练历史", "动态"), tab, Modifier.padding(horizontal = 16.dp), tags = listOf("timeline-tab-history", "timeline-tab-posts")) { tab = it }
        Box(Modifier.weight(1f)) {
            if (tab == 1) SocialTimelineScreen(repository, me, owner = owner.id, onBack = onBack, onPerson = onPerson, embedded = true)
            else if (ownHistory != null) Column(Modifier.fillMaxSize().padding(top = 8.dp)) { ownHistory() }
            else SharedHistoryView(repository, owner.id, sharingHistory)
        }
    }
}

/**
 * Full profile editing: the editor header used by templates, the member card as a live preview, a key to replace the
 * avatar, nickname and bio with counts, and the banner choice. The banner (and a picked photo, uploaded only then) is
 * saved with the rest; the avatar still uploads as soon as it is picked.
 */
@Composable
fun EditProfileScreen(repository: SocialRepository?, profile: SocialProfile, busy: Boolean, onClose: () -> Unit, onSave: (String, String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.nickname) }
    var bio by rememberSaveable(profile.id) { mutableStateOf(profile.bio) }
    val savedBanner = profile.banner?.let { if (ProfileBanners.isImage(it)) BANNER_PHOTO else ProfileBanners.scene(it) }.orEmpty()
    var banner by rememberSaveable(profile.id) { mutableStateOf(savedBanner) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    val photoPreview = remember(photo) { photo?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    var uploading by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val bannerChanged = banner != savedBanner || (banner == BANNER_PHOTO && photo != null)
    val dirty = name.trim() != profile.nickname || bio.trim() != profile.bio || bannerChanged
    val cancel = { if (dirty) leaving = true else onClose() }
    BackHandler { cancel() }
    val avatarPicker = if (repository != null) rememberImagePicker(onImage = { bytes ->
        uploading = true
        try { repository.saveAvatar(repository.uploadImage(bytes)) } finally { uploading = false }
    }, onError = { error = it }) else ({})
    // A picked photo goes to the framing page first; only the framed area is kept.
    var framing by remember { mutableStateOf<ByteArray?>(null) }
    val framingImage = remember(framing) { framing?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val bannerPicker = if (repository != null) rememberImagePicker(onImage = { bytes -> framing = bytes }, onError = { error = it }) else ({})
    framingImage?.let { image ->
        BannerCropper(image, onCancel = { framing = null }) { area ->
            val source = framing ?: return@BannerCropper
            framing = null
            scope.launch {
                preparing = true
                try { photo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { bannerCrop(source, area) }; banner = BANNER_PHOTO }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "图片处理失败" }
                finally { preparing = false }
            }
        }
    }
    val save: () -> Unit = {
        scope.launch {
            uploading = true; error = null
            try {
                if (bannerChanged && repository != null) {
                    val target = when (banner) {
                        BANNER_PHOTO -> photo?.let { repository.uploadImage(it) } ?: profile.banner
                        "" -> null
                        else -> banner
                    }
                    repository.saveBanner(target)
                }
                onSave(name.trim(), bio.trim())
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "保存失败，请重试" }
            finally { uploading = false }
        }
    }
    val preview = profile.copy(nickname = name.trim().ifEmpty { profile.nickname }, bio = bio.trim(),
        banner = when (banner) { BANNER_PHOTO -> profile.banner?.takeIf { ProfileBanners.isImage(it) }; "" -> null; else -> banner })
    Column(Modifier.fillMaxSize().testTag("edit-profile")) {
        EditorHeader("修改资料", "保存资料", dirty, busy || uploading, onCancel = cancel, onSave = save)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ProfileHero(repository, preview, editable = false, pendingBanner = if (banner == BANNER_PHOTO) photoPreview else null, tag = "edit-preview",
                corner = { PixelOutlineKey(onClick = avatarPicker, enabled = !uploading && repository != null, minHeight = 30.dp,
                    modifier = Modifier.testTag("change-avatar")) {
                    LineIcon(Symbol.IMAGE, Modifier.size(12.dp), LocalContentColor.current); Text(if (uploading) "上传中…" else "更换头像", maxLines = 1)
                } })
            error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            CountedField("昵称", name, 40, singleLine = true, tag = "profile-name") { name = it }
            CountedField("简介", bio, 300, singleLine = false, tag = "profile-bio-field") { bio = it }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("主页背景", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                BannerPicker(repository, banner, profile.banner?.takeIf { ProfileBanners.isImage(it) }, photoPreview, preparing,
                    onChoose = { banner = it }, onAlbum = bannerPicker)
            }
        }
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("放弃这次修改？") },
        confirmButton = { TextButton(onClick = { leaving = false; onClose() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("继续编辑") } })
}

@Composable
private fun CountedField(label: String, value: String, limit: Int, singleLine: Boolean, tag: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Text("${value.length}/$limit", style = pixelNumber(15.sp), color = scheme.onSurfaceVariant)
        }
        OutlinedTextField(value, { onChange(it.take(limit)) }, Modifier.fillMaxWidth().testTag(tag), singleLine = singleLine,
            minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 6,
            textStyle = if (singleLine) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium)
    }
}
