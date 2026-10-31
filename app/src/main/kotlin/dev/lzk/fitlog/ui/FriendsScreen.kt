package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import kotlinx.coroutines.*

@Composable
fun FriendsScreen(model:FriendsViewModel,social:SocialRepository?=null,
    onLogin:()->Unit={},onSubpage:(Boolean)->Unit={}) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val friends by model.friends.collectAsStateWithLifecycle()
    val found by model.found.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var search by rememberSaveable {mutableStateOf("")}
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var chatPeer by rememberSaveable {mutableStateOf<String?>(null)}
    var timeline by rememberSaveable {mutableStateOf(false)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model,profile?.id,lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(isActive) {if(model.profile.value!=null)model.refresh();delay(15_000)}
        }
    }
    LaunchedEffect(selectedId,chatPeer,timeline){onSubpage(selectedId!=null || chatPeer!=null || timeline)}
    DisposableEffect(Unit){onDispose{onSubpage(false)}}
    if(profile==null) LaunchedEffect(Unit){selectedId=null;chatPeer=null;timeline=false}
    if(social!=null && profile!=null && (selectedId!=null || timeline)) {
        SocialTimelineScreen(social,requireNotNull(profile),owner=selectedId,
            friend=friends.find{it.profile.id==selectedId},
            onBack={if(selectedId!=null)selectedId=null else timeline=false},
            onPerson={selectedId=it},onNote={note->selectedId?.let{model.saveNote(it,note)}},
            onRemove={selectedId?.let{model.act(it,"remove")};selectedId=null;chatPeer=null},
            onBlock={selectedId?.let{model.act(it,"block")};selectedId=null;chatPeer=null})
        return
    }
    val chatting=friends.find{it.profile.id==chatPeer}
    if(social!=null && profile!=null && chatting!=null) {
        ChatScreen(social,requireNotNull(profile),chatting,friends,{chatPeer=null},{selectedId=it})
        return
    }
    val selected=friends.find{it.profile.id==selectedId}
    var confirming by remember {mutableStateOf<Pair<String,String>?>(null)}
    if(selectedId!=null) BackHandler{selectedId=null}
    LazyColumn(Modifier.fillMaxSize().testTag("friends-list"),contentPadding=PaddingValues(20.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(selectedId!=null) IconButton(onClick={selectedId=null}){
                    LineIcon(Symbol.BACK,Modifier.size(24.dp).semanticsLabel("返回好友列表"))
                }
                OneLineText(if(selectedId!=null)"个人主页" else "好友",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)

            }

            message?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
        }
        if(profile==null) item {
            SoftCard {
                Text("登录后，和好友一起进步",style=MaterialTheme.typography.titleMedium)
                Text("账号与个人资料已移至「我的」。",style=MaterialTheme.typography.bodySmall)
                Button(onClick=onLogin){Text("去登录")}
            }
        } else if(selected!=null) item {
            var note by rememberSaveable(selected.profile.id,selected.note){mutableStateOf(selected.note)}
            SoftCard {
                ProfileIdentity(selected.profile)
                OutlinedTextField(note,{note=it.take(40)},label={Text("好友备注（仅自己可见）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(onClick={model.saveNote(selected.profile.id,note)},enabled=!busy){Text("保存备注")}
                TextButton(onClick={confirming=selected.profile.id to "remove"}){Text("删除好友")}
                TextButton(onClick={confirming=selected.profile.id to "block"}){Text("拉黑")}
            }
        } else {
            if(social!=null) item {Button(onClick={timeline=true},modifier=Modifier.fillMaxWidth()){Text("查看动态")}}
            item {
                OutlinedTextField(search,{search=it.trim()},label={Text("输入好友的完整 FitLog ID")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("friend-search"))
                TextButton(onClick={model.search(search)},enabled=!busy && search.isNotBlank()){Text("查找好友")}
                found?.let{person->
                    SoftCard {
                        Text(person.nickname);Text(person.fitlogId,style=MaterialTheme.typography.bodySmall)
                        Button(onClick={model.act(person.id,"request")},enabled=!busy){Text("发送好友申请")}
                    }
                }
            }
            val requests=friends.filter{!it.accepted}
            if(requests.isNotEmpty())item{Text("好友申请",style=MaterialTheme.typography.titleMedium)}
            items(requests,key={"request:"+it.profile.id}){friend->
                SoftCard {
                    OneLineText(friend.profile.nickname,style=MaterialTheme.typography.titleMedium)
                    Text(if(friend.incoming)"请求添加你为好友" else "等待对方接受")
                    Row {
                        if(friend.incoming) {
                            TextButton(onClick={model.act(friend.profile.id,"accept")},enabled=!busy){Text("接受")}
                            TextButton(onClick={model.act(friend.profile.id,"reject")},enabled=!busy){Text("拒绝")}
                        }else TextButton(onClick={model.act(friend.profile.id,"cancel")},enabled=!busy){Text("取消申请")}
                    }
                }
            }
            val accepted=friends.filter{it.accepted}
            item{Text("我的好友 · ${accepted.size}",style=MaterialTheme.typography.titleMedium)}
            if(accepted.isEmpty())item{Text("还没有好友，输入对方的 FitLog ID 发起申请。",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(accepted,key={it.profile.id}){friend->
                SoftCard(Modifier.clickable{if(social==null)selectedId=friend.profile.id else chatPeer=friend.profile.id}) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        SocialAvatar(social,friend.profile){selectedId=friend.profile.id}
                        Column(Modifier.weight(1f)) {
                            OneLineText(friend.displayName,style=MaterialTheme.typography.titleMedium)
                            OneLineText(friend.presence.label+(if(friend.unread>0)" · ${friend.unread} 条未读" else ""),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                            OneLineText(friend.lastMessage ?: friend.profile.bio.ifBlank{"查看个人主页"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        LineIcon(Symbol.ARROW,Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    confirming?.let{(id,action)->
        AlertDialog(onDismissRequest={confirming=null},title={Text(if(action=="block")"拉黑此好友？" else "删除好友？")},
            text={Text("好友关系将移除，停止后续共享。")},
            confirmButton={TextButton(onClick={confirming=null;selectedId=null;model.act(id,action)},enabled=!busy){Text("确认")}},
            dismissButton={TextButton(onClick={confirming=null}){Text("取消")}})
    }
}

@Composable internal fun ProfileIdentity(profile:SocialProfile) {
    OneLineText(profile.nickname,style=MaterialTheme.typography.titleLarge)
    FitLogIdentityRow(profile.fitlogId)
}
