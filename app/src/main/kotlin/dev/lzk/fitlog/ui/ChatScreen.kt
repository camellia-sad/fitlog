package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.ChatController
import kotlinx.coroutines.*
import java.util.UUID

@Composable
fun ChatScreen(repository:SocialRepository,me:SocialProfile,friend:Friend,friends:List<Friend>,onBack:()->Unit,onPerson:(String)->Unit) {
    val scope=rememberCoroutineScope()
    val controller=remember(repository,me.id,friend.profile.id) {ChatController(repository,friend.profile.id,scope)}
    val messages by controller.messages.collectAsState()
    val pending by controller.pending.collectAsState()
    val error by controller.error.collectAsState()
    val busy by controller.busy.collectAsState()
    val browsing by controller.browsingHistory.collectAsState()
    val hasOlder by controller.hasOlder.collectAsState()
    var text by rememberSaveable(me.id,friend.profile.id) {mutableStateOf("")}
    var reply by remember {mutableStateOf<ChatMessage?>(null)}
    var menu by remember {mutableStateOf<ChatMessage?>(null)}
    var confirming by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    var forwarding by remember {mutableStateOf(false)}
    var forwardRequest by remember {mutableStateOf(UUID.randomUUID().toString())}
    var locate by remember {mutableStateOf<String?>(null)}
    var locateMessage by remember {mutableStateOf<String?>(null)}
    val list=rememberLazyListState()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {while(isActive) {controller.refresh();delay(5_000)}}
            finally {controller.release()}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {scope.coroutineContext.cancelChildren();controller.release();reply=null;menu=null;selected=emptySet()}
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    val picker=rememberImagePicker(onImage={jpeg->
        val path=repository.uploadImage(jpeg)
        controller.send(MessageDraft(body=text,mediaPath=path,replyId=reply?.id)) {text="";reply=null}
    },onError={controller.error.value=it})
    BackHandler(onBack=onBack)
    LaunchedEffect(locate,messages,busy,error) {
        if(error!=null) {locate=null;return@LaunchedEffect}
        val id=locate ?: return@LaunchedEffect
        val index=messages.indexOfFirst {it.id==id}
        if(index>=0) {list.animateScrollToItem(index+1);locateMessage="已定位原消息";locate=null}
        else if(!busy) {controller.locate(id)}
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal=16.dp).testTag("chat-screen")) {
        SocialHeader(friend.displayName,onBack) {TextButton(onClick={confirming="clear"}) {Text("清空")}}
        Text(friend.presence.label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        locateMessage?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(selected.isNotEmpty()) Row {
            TextButton(onClick={forwarding=true;forwardRequest=UUID.randomUUID().toString()}) {Text("转发 ${selected.size} 条")}
            TextButton(onClick={selected=emptySet()}) {Text("取消选择")}
        }
        if(browsing) TextButton(onClick=controller::latest) {Text("回到最新消息")}
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,reverseLayout=true,
            verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)) {
            item {
                Column {
                    pending.forEach {draft->
                        SoftCard {
                            Text(draft.body.ifBlank {"[图片]"},maxLines=3)
                            Text("待发送",color=MaterialTheme.colorScheme.error)
                            Row {
                                TextButton(onClick={controller.retry(draft)},enabled=!busy){Text("重试")}
                                TextButton(onClick={controller.discard(draft)},enabled=!busy){Text("删除")}
                            }
                        }
                    }
                }
            }
            items(messages,key={it.id}) {message->
                val mine=message.sender==me.id
                Row(horizontalArrangement=if(mine) Arrangement.End else Arrangement.Start,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.widthIn(max=300.dp)) {
                        Row {
                            if(!mine) SocialAvatar(repository,friend.profile){onPerson(friend.profile.id)}
                            Text(message.createdAt.socialTime(),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(8.dp))
                            if(message.id in selected) Text("✓",color=MaterialTheme.colorScheme.primary)
                        }
                        SoftCard(Modifier.combinedClickable(onClick={
                            if(selected.isNotEmpty()) {
                                if(message.forwarded.isNotEmpty()) controller.error.value="聊天记录请单独转发"
                                else selected=if(message.id in selected) selected-message.id
                                    else if(selected.size<20) selected+message.id else selected
                            }
                        },onLongClick={menu=message})) {
                            if(message.withdrawn) Text("消息已撤回",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                message.replyId?.let {original->
                                    TextButton(onClick={locate=original}) {
                                        val source=messages.find {it.id==original}
                                        Text("引用 · "+if(source?.withdrawn==true) "原消息已撤回" else message.replyPreview.orEmpty(),
                                            maxLines=2,style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if(message.body.isNotBlank()) Text(message.body)
                                message.mediaPath?.let {SocialImage(repository,it,Modifier.fillMaxWidth().height(180.dp),onLongClick={menu=message})}
                                if(message.forwarded.isNotEmpty()) {
                                    Text("聊天记录",style=MaterialTheme.typography.titleSmall)
                                    message.forwarded.forEach {part->
                                        Text(part.sender+"："+part.body,maxLines=5)
                                        part.mediaPath?.let {SocialImage(repository,it,Modifier.fillMaxWidth().height(140.dp),onLongClick={menu=message})}
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {TextButton(onClick=controller::older,enabled=!busy && hasOlder) {Text(if(hasOlder) "加载更早消息" else "没有更早消息")}}
        }
        reply?.let {source->
            Row {
                Text("回复："+source.body.ifBlank {"[图片或记录]"},Modifier.weight(1f),maxLines=1)
                TextButton(onClick={reply=null}) {Text("取消")}
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom=8.dp).testTag("chat-composer"),verticalAlignment=androidx.compose.ui.Alignment.Bottom) {
            TextButton(onClick=picker,enabled=!busy) {Text("图片")}
            OutlinedTextField(text,{text=it.take(8000)},modifier=Modifier.weight(1f).testTag("chat-input"),maxLines=4,placeholder={Text("发消息")})
            TextButton(onClick={
                val draft=MessageDraft(body=text,replyId=reply?.id)
                controller.send(draft) {text="";reply=null}
            },enabled=!busy && text.isNotBlank()) {Text("发送")}
        }
    }
    menu?.let {message->
        AlertDialog(onDismissRequest={menu=null},title={Text("消息操作")},text={
            Column {
                if(!message.withdrawn) {
                    TextButton(onClick={reply=message;menu=null}) {Text("引用回复")}
                    TextButton(onClick={selected=setOf(message.id);menu=null;forwarding=true;forwardRequest=UUID.randomUUID().toString()}) {Text("转发")}
                    if(message.forwarded.isEmpty()) TextButton(onClick={selected=setOf(message.id);menu=null}) {Text("多选合并转发")}
                    if(message.sender==me.id) TextButton(onClick={controller.withdraw(message);menu=null}) {Text("撤回")}
                }
                TextButton(onClick={controller.delete(message);menu=null}) {Text("仅从我的所有设备删除")}
            }
        },confirmButton={TextButton(onClick={menu=null}){Text("关闭")}})
    }
    if(forwarding) AlertDialog(onDismissRequest={forwarding=false},title={Text("转发给好友")},text={
        LazyColumn {
            items(friends.filter {it.accepted},key={it.profile.id}) {target->
                TextButton(onClick={controller.action {
                    repository.forward(target.profile.id,messages.filter {it.id in selected}.sortedBy {it.createdAt}.map {it.id},forwardRequest)
                    forwarding=false;selected=emptySet()
                }},enabled=!busy) {Text(target.displayName)}
            }
        }
    },confirmButton={TextButton(onClick={forwarding=false}){Text("取消")}})
    if(confirming=="clear") AlertDialog(onDismissRequest={confirming=null},title={Text("清空聊天记录？")},
        text={Text("只从自己的所有设备清空，对方的记录保留。")},
        confirmButton={TextButton(onClick={controller.clear();confirming=null}){Text("清空")}},
        dismissButton={TextButton(onClick={confirming=null}){Text("取消")}})
}
