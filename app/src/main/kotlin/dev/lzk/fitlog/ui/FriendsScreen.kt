package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import dev.lzk.fitlog.features.FriendsViewModel

@Composable
fun FriendsScreen(model:FriendsViewModel, social:SocialRepository?=null,
    sharing:dev.lzk.fitlog.features.TrainingSharing?=null,onSubpage:(Boolean)->Unit={}) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val friends by model.friends.collectAsStateWithLifecycle()
    val found by model.found.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val codeSent by model.codeSent.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSelf by rememberSaveable { mutableStateOf(false) }
    var chatPeer by rememberSaveable {mutableStateOf<String?>(null)}
    var timeline by rememberSaveable {mutableStateOf(false)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model,profile?.id,lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(15_000)
                if(model.profile.value!=null) model.refresh()
            }
        }
    }
    LaunchedEffect(selectedId,editingSelf,chatPeer,timeline) {
        onSubpage(selectedId!=null || editingSelf || chatPeer!=null || timeline)
    }
    DisposableEffect(Unit) {onDispose {onSubpage(false)}}
    if(profile==null) LaunchedEffect(Unit) {selectedId=null;chatPeer=null;timeline=false;editingSelf=false}
    if(social!=null && profile!=null && (selectedId!=null || timeline)) {
        SocialTimelineScreen(social,requireNotNull(profile),owner=selectedId,
            friend=friends.find {it.profile.id==selectedId},
            onBack={if(selectedId!=null)selectedId=null else timeline=false},
            onPerson={selectedId=it},
            onNote={note->selectedId?.let {model.saveNote(it,note)}},
            onRemove={selectedId?.let {model.act(it,"remove")};selectedId=null;chatPeer=null},
            onBlock={selectedId?.let {model.act(it,"block")};selectedId=null;chatPeer=null})
        return
    }
    val chatting=friends.find {it.profile.id==chatPeer}
    if(social!=null && profile!=null && chatting!=null) {
        ChatScreen(social,requireNotNull(profile),chatting,friends,onBack={chatPeer=null},onPerson={selectedId=it})
        return
    }
    val selected=friends.find { it.profile.id==selectedId }
    var confirming by remember { mutableStateOf<Pair<String,String>?>(null) }
    if(selectedId!=null || editingSelf) BackHandler { selectedId=null; editingSelf=false }
    LazyColumn(Modifier.fillMaxSize().testTag("friends-list"),contentPadding=PaddingValues(20.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(selectedId!=null || editingSelf) IconButton(onClick={ selectedId=null; editingSelf=false }) {
                    LineIcon(Symbol.BACK,Modifier.size(24.dp).semanticsLabel("返回好友列表"))
                }
                OneLineText(if(selectedId!=null || editingSelf) "个人主页" else "好友",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)
                if(profile!=null) TextButton(onClick=model::refresh,enabled=!busy) { Text("刷新") }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it,color=MaterialTheme.colorScheme.primary) }
        }
        if(!model.configured) item {
            Text("好友服务尚未配置，离线训练仍可照常使用。")
        } else if(profile==null) {
            item {
                SoftCard {
                    OneLineText("登录，和好友一起进步",style=MaterialTheme.typography.titleLarge)
                    Text("登录仅用于社交，不会自动上传本机训练历史。",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(email,{ email=it.trim(); code="" },label={ Text("邮箱") },singleLine=true,
                        enabled=!busy && !codeSent,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
                    if(!codeSent) TextButton(onClick={ model.codeSent.value=true },enabled=!busy && email.isNotBlank()) { Text("已有验证码") }
                    if(codeSent) {
                        OutlinedTextField(code,{ code=it },label={ Text("邮件验证码") },singleLine=true,
                            enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                        Button(onClick={ model.verify(email,code) },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("验证并登录") }
                        TextButton(onClick={ model.codeSent.value=false; code="" },enabled=!busy) { Text("更换邮箱") }
                    }
                    OutlinedButton(onClick={ model.requestCode(email) },enabled=!busy && email.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                        Text(if(codeSent) "重新发送验证码" else "发送验证码")
                    }
                }
            }
        } else if(editingSelf) {
            item {
                val current=requireNotNull(profile)
                var name by rememberSaveable(current.id) { mutableStateOf(current.nickname) }
                var bio by rememberSaveable(current.id) { mutableStateOf(current.bio) }
                SoftCard {
                    if(social!=null) { AvatarEditor(social,current); TextButton(onClick={selectedId=current.id}){Text("我的动态与共享历史")} }
                    ProfileIdentity(current)
                    OutlinedTextField(name,{name=it.take(40)},label={Text("昵称")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(bio,{bio=it.take(300)},label={Text("简介")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={model.saveProfile(name,bio)},enabled=!busy){Text("保存资料")}
                    if(social!=null && sharing!=null) SharingControls(model,social) {sharing.syncHistory()}
                    if(social!=null) BlockedFriends(social,model)
                    OutlinedButton(onClick={confirming="" to "logout"},enabled=!busy){Text("退出登录")}
                }
            }
        } else if(selected!=null) {
            item {
                var note by rememberSaveable(selected.profile.id,selected.note) { mutableStateOf(selected.note) }
                SoftCard {
                    ProfileIdentity(selected.profile)
                    if(selected.profile.bio.isNotBlank()) Text(selected.profile.bio)
                    OutlinedTextField(note,{note=it.take(40)},label={Text("好友备注（仅自己可见）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={model.saveNote(selected.profile.id,note)},enabled=!busy){Text("保存备注")}
                    TextButton(onClick={confirming=selected.profile.id to "remove"},enabled=!busy){Text("删除好友")}
                    TextButton(onClick={confirming=selected.profile.id to "block"},enabled=!busy){Text("拉黑")}
                }
            }
        } else {
            item {
                SoftCard(Modifier.clickable { editingSelf=true }) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        FriendAvatar(requireNotNull(profile).nickname) { editingSelf=true }
                        Column(Modifier.weight(1f)) {
                            OneLineText(requireNotNull(profile).nickname,style=MaterialTheme.typography.titleMedium)
                            Text("我的资料与 FitLog ID",style=MaterialTheme.typography.bodySmall)
                        }
                        LineIcon(Symbol.ARROW,Modifier.size(20.dp))
                    }
                }
            }
            item {
                OutlinedTextField(search,{search=it.trim()},label={Text("输入好友的完整 FitLog ID")},singleLine=true,
                    modifier=Modifier.fillMaxWidth().testTag("friend-search"))
                TextButton(onClick={model.search(search)},enabled=!busy && search.isNotBlank()){Text("查找好友")}
                found?.let { person ->
                    SoftCard {
                        Text(person.nickname)
                        Text(person.fitlogId,style=MaterialTheme.typography.bodySmall)
                        Button(onClick={model.act(person.id,"request")},enabled=!busy){Text("发送好友申请")}
                    }
                }
            }
            if(social!=null) item {
                Button(onClick={timeline=true},modifier=Modifier.fillMaxWidth()){Text("查看动态")}
            }
            val requests=friends.filter { !it.accepted }
            if(requests.isNotEmpty()) item { Text("好友申请",style=MaterialTheme.typography.titleMedium) }
            items(requests,key={"request:"+it.profile.id}) { friend ->
                SoftCard {
                    OneLineText(friend.profile.nickname,style=MaterialTheme.typography.titleMedium)
                    Text(if(friend.incoming) "请求添加你为好友" else "等待对方接受")
                    Row {
                        if(friend.incoming) {
                            TextButton(onClick={model.act(friend.profile.id,"accept")},enabled=!busy){Text("接受")}
                            TextButton(onClick={model.act(friend.profile.id,"reject")},enabled=!busy){Text("拒绝")}
                        } else TextButton(onClick={model.act(friend.profile.id,"cancel")},enabled=!busy){Text("取消申请")}
                    }
                }
            }
            val accepted=friends.filter { it.accepted }
            item { Text("我的好友 · ${accepted.size}",style=MaterialTheme.typography.titleMedium) }
            if(accepted.isEmpty()) item { Text("还没有好友，输入对方的 FitLog ID 发起申请。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            items(accepted,key={it.profile.id}) { friend ->
                SoftCard(Modifier.clickable {if(social==null)selectedId=friend.profile.id else chatPeer=friend.profile.id}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        SocialAvatar(social,friend.profile) {selectedId=friend.profile.id}
                        Column(Modifier.weight(1f)) {
                            OneLineText(friend.displayName,style=MaterialTheme.typography.titleMedium)
                            OneLineText(friend.presence.label+(if(friend.unread>0) " · ${friend.unread} 条未读" else ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                            OneLineText(friend.lastMessage ?: friend.profile.bio.ifBlank { "查看个人主页" },style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LineIcon(Symbol.ARROW,Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    confirming?.let { (id,action) ->
        AlertDialog(onDismissRequest={confirming=null},title={Text(when(action){"logout"->"退出登录？";"block"->"拉黑此好友？";else->"删除好友？"})},
            text={Text(if(action=="logout") "本机训练记录会保留；待发送社交消息和缓存将清除。" else "好友关系将移除，停止后续共享。")},
            confirmButton={TextButton(onClick={
                confirming=null
                if(action=="logout") { editingSelf=false; model.signOut() }
                else { selectedId=null; model.act(id,action) }
            },enabled=!busy){Text("确认")}},
            dismissButton={TextButton(onClick={confirming=null}){Text("取消")}})
    }
}

@Composable
private fun ProfileIdentity(profile:SocialProfile) {
    OneLineText(profile.nickname,style=MaterialTheme.typography.titleLarge)
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text("FitLog ID：${profile.fitlogId}",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun FriendAvatar(name:String,onClick:()->Unit) {
    Surface(onClick=onClick,shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(48.dp)) {
        Box(contentAlignment=Alignment.Center) {
            Text(name.take(1),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.primary)
        }
    }
}
