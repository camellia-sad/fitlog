package dev.lzk.fitlog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import kotlinx.coroutines.*

/**
 * Settings group “社交与隐私”: what friends can see of one's training, the block list and signing out.
 * Signed out, the group holds a single line.
 */
@Composable
internal fun SocialPrivacyRows(model: FriendsViewModel, repository: SocialRepository?, syncHistory: suspend () -> Unit) {
    val profile by model.profile.collectAsStateWithLifecycle()
    if (profile == null) {
        ListRow(Symbol.FRIENDS, "登录后可用", chevron = false, onClick = null, modifier = Modifier.testTag("social-signed-out"))
        return
    }
    val scope = rememberCoroutineScope()
    var privacy by remember { mutableStateOf(SharingPreferences()) }
    var confirm by remember { mutableStateOf<SharingPreferences?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var logout by remember { mutableStateOf(false) }
    fun launch(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; message = null
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(profile?.id) { if (repository != null) launch { privacy = model.repositorySharing() } }
    fun change(value: SharingPreferences) {
        if ((value.history || value.live) && repository?.boundTrainingOwner() != model.profile.value?.id) confirm = value
        else launch { model.updateSharing(value); privacy = value }
    }
    if (repository != null) {
    SettingRow("共享训练历史", trailing = {
        PixelSwitch(privacy.history, "共享训练历史", Modifier.testTag("share-history-switch"), enabled = !busy) { change(privacy.copy(history = it)) }
    }) {
        if (privacy.history) PixelOutlineKey(onClick = { launch { syncHistory(); message = "训练历史已同步" } }, enabled = !busy, minHeight = 34.dp,
            modifier = Modifier.testTag("sync-history")) {
            LineIcon(Symbol.CLOUD, Modifier.size(13.dp), LocalContentColor.current); Text(if (busy) "同步中…" else "同步全部训练历史", maxLines = 1)
        }
    }
    SettingRow("共享实时训练详情", trailing = {
        PixelSwitch(privacy.live, "共享实时训练详情", Modifier.testTag("share-live-switch"), enabled = !busy) { change(privacy.copy(live = it)) }
    })
    message?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    BlockedList(repository, model)
    }
    ListRow(Symbol.EXPORT, "退出登录", tone = RowTone.DANGER, chevron = false, modifier = Modifier.testTag("sign-out")) { logout = true }
    confirm?.let { value ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("绑定本机训练记录？") },
            text = { Text("本机现有及后续训练将关联当前 FitLog 账号。仅开启的共享项会上传，切换账号后需重新确认。") },
            confirmButton = { TextButton(onClick = { confirm = null; launch {
                repository?.bindTrainingOwner(); model.updateSharing(value); privacy = value
            } }) { Text("确认绑定并开启") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出登录？") },
        text = { Text("本机训练、聊天记录和待发送消息会保留，按账号隔离。") },
        confirmButton = { TextButton(onClick = { logout = false; model.signOut() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
}

/** The block list folds into one row with a pixel count; opened, each person can be unblocked in place. */
@Composable
private fun BlockedList(repository: SocialRepository, model: FriendsViewModel) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<SocialProfile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() { try { people = repository.blocked(); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    LaunchedEffect(Unit) { load() }
    Row(Modifier.fillMaxWidth().clickable { open = !open; if (open) scope.launch { load() } }.padding(horizontal = 14.dp, vertical = 12.dp).testTag("blocked-toggle"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("黑名单", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        people?.let { Text("${it.size}", style = pixelNumber(20.sp), color = scheme.onSurface) }
        LineIcon(Symbol.ARROW, Modifier.size(12.dp).rotate(if (open) 90f else 0f), scheme.onSurfaceVariant)
    }
    AnimatedVisibility(open, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(150))) {
        Column(Modifier.fillMaxWidth().testTag("blocked-list")) {
            error?.let { Text(it, Modifier.padding(horizontal = 14.dp), color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            val list = people.orEmpty()
            if (list.isEmpty()) Text("没有已拉黑的人", Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            list.forEach { person ->
                HorizontalDivider(color = scheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SocialAvatar(repository, person, size = 32.dp) {}
                    Column(Modifier.weight(1f)) {
                        Text(person.nickname, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(person.fitlogId, style = pixelNumber(14.sp), color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                    PixelOutlineKey(onClick = {
                        if (!busy) scope.launch {
                            busy = true
                            try { model.unblock(person.id); load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    }, enabled = !busy, minHeight = 30.dp, modifier = Modifier.testTag("unblock-${person.id}")) { Text("解除", maxLines = 1) }
                }
            }
        }
    }
    HorizontalDivider(color = scheme.outlineVariant)
}
