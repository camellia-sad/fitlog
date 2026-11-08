package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel

/**
 * “我的”: the person first — a large avatar and nickname that edit in place, a quieter bio and the FitLog ID bar — then
 * three read-only figures and two entries. Sharing, the block list and signing out live in settings.
 */
@Composable
fun MeScreen(model: FriendsViewModel, social: SocialRepository? = null, onSettings: () -> Unit = {}, onSubpage: (Boolean) -> Unit = {},
    figures: List<Pair<String, String>> = emptyList(), ownHistory: (@Composable () -> Unit)? = null) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var personId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(page, personId) { onSubpage(page != null || personId != null) }
    DisposableEffect(Unit) { onDispose { onSubpage(false) } }
    LaunchedEffect(profile?.id) { if (profile == null) { page = null; personId = null } }
    val current = profile
    val friends by model.friends.collectAsStateWithLifecycle()
    if (social != null && current != null) {
        val other: (String) -> Unit = { id -> if (id != current.id) personId = id }
        personId?.let { id ->
            PersonScreen(social, current, id, friends.find { it.profile.id == id }, onBack = { personId = null },
                onChat = null, onNote = { model.saveNote(id, it) }, onRemove = { model.act(id, "remove"); personId = null },
                onBlock = { model.act(id, "block"); personId = null }, onPerson = other)
            return
        }
        if (page == "timeline") { ProfileTimelineScreen(social, current, current, null, ownHistory, onBack = { page = null }, onPerson = other); return }
    }
    if (current != null && page == "edit") {
        EditProfileScreen(social, current, busy, onClose = { page = null }) { name, bio -> model.saveProfile(name, bio); page = null }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 24.dp).testTag("me-list"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        if (current != null) {
            ProfileHero(social, current, editable = true,
                onNickname = { if (it.isNotBlank() && it != current.nickname) model.saveProfile(it, current.bio) },
                onBio = { if (it != current.bio) model.saveProfile(current.nickname, it) },
                action = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PixelOutlineKey(onClick = { page = "edit" }, minHeight = 32.dp, contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("open-edit-profile")) {
                            LineIcon(Symbol.EDIT, Modifier.size(12.dp), LocalContentColor.current); Text("编辑资料", maxLines = 1)
                        }
                    }
                })
            if (figures.isNotEmpty()) ProfileFigures(figures)
            ProfileEntries {
                if (social != null) {
                    ListRow(Symbol.LIST, "动态与训练历史", tone = RowTone.HERO, modifier = Modifier.testTag("open-my-timeline")) { page = "timeline" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ListRow(Symbol.SETTINGS, "设置", modifier = Modifier.testTag("open-settings"), onClick = onSettings)
            }
        } else {
            if (model.configured) LoginCard(model)
            else Text("账号服务尚未配置，离线训练仍可照常使用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProfileEntries { ListRow(Symbol.SETTINGS, "设置", modifier = Modifier.testTag("open-settings"), onClick = onSettings) }
        }
    }
}

@Composable private fun LoginCard(model: FriendsViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val codeSent by model.codeSent.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    SoftCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PixelPuppy(Modifier.size(72.dp))
            Text("登录，和好友一起进步", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), textAlign = TextAlign.Center)
            Text("只用于社交，不上传本机训练", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(email, { email = it.trim(); code = "" }, label = { Text("邮箱") }, singleLine = true,
            enabled = !busy && !codeSent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        if (!codeSent) TextButton(onClick = { model.codeSent.value = true }, enabled = !busy && email.isNotBlank()) { Text("已有验证码") }
        if (codeSent) {
            OutlinedTextField(code, { code = it }, label = { Text("邮件验证码") }, singleLine = true,
                enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            PixelButton(onClick = { model.verify(email, code) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("验证并登录") }
            TextButton(onClick = { model.codeSent.value = false; code = "" }, enabled = !busy) { Text("更换邮箱") }
        }
        if (codeSent) TextButton(onClick = { model.requestCode(email) }, enabled = !busy && email.isNotBlank()) { Text("重新发送验证码") }
        else PixelButton(onClick = { model.requestCode(email) }, enabled = !busy && email.isNotBlank(), modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) {
            Text("发送验证码")
        }
    }
}
