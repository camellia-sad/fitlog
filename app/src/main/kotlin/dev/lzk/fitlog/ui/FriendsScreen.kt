package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import kotlinx.coroutines.*

/**
 * Friends as a conversation list. The title bar holds the feed (with unread replies) and adding a friend; friends who
 * are online or training line up as avatars above the list, and tapping one opens the chat. Each friend is one row:
 * avatar with presence, name, a quiet time and the last message with an unread count.
 */
@Composable
fun FriendsScreen(model:FriendsViewModel,social:SocialRepository?=null,
    onLogin:()->Unit={},onSubpage:(Boolean)->Unit={}) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val friends by model.friends.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable {mutableStateOf<String?>(null)}
    var chatPeer by rememberSaveable {mutableStateOf<String?>(null)}
    var timeline by rememberSaveable {mutableStateOf(false)}
    var adding by rememberSaveable {mutableStateOf(false)}
    var unreadReplies by remember {mutableIntStateOf(0)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model,profile?.id,lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(isActive) {
                if(model.profile.value!=null) {
                    model.refresh()
                    if(social!=null) try {unreadReplies=social.unreadReplies()} catch(e:CancellationException) {throw e} catch(_:Exception) {}
                }
                delay(15_000)
            }
        }
    }
    LaunchedEffect(selectedId,chatPeer,timeline){onSubpage(selectedId!=null || chatPeer!=null || timeline)}
    DisposableEffect(Unit){onDispose{onSubpage(false)}}
    if(profile==null) LaunchedEffect(Unit){selectedId=null;chatPeer=null;timeline=false;adding=false}
    val me=profile
    if(social!=null && me!=null && selectedId!=null) {
        val id=requireNotNull(selectedId)
        val friend=friends.find{it.profile.id==id}
        PersonScreen(social,me,id,friend,onBack={selectedId=null},
            onChat=friend?.takeIf{it.accepted}?.let{{chatPeer=id;selectedId=null}},
            onNote={model.saveNote(id,it)},
            onRemove={model.act(id,"remove");selectedId=null;chatPeer=null},
            onBlock={model.act(id,"block");selectedId=null;chatPeer=null},
            onPerson={if(it!=me.id)selectedId=it})
        return
    }
    if(social!=null && me!=null && timeline) {
        SocialTimelineScreen(social,me,owner=null,onBack={timeline=false},onPerson={if(it!=me.id)selectedId=it})
        return
    }
    val chatting=friends.find{it.profile.id==chatPeer}
    if(social!=null && me!=null && chatting!=null) {
        ChatScreen(social,me,chatting,friends,{chatPeer=null},{selectedId=it})
        return
    }
    val selected=friends.find{it.profile.id==selectedId}
    var confirming by remember {mutableStateOf<Pair<String,String>?>(null)}
    if(selectedId!=null) BackHandler{selectedId=null}
    // Without the social layer (tests, unconfigured builds) rows open a plain profile card instead of the chat.
    val open:(Friend)->Unit={if(social==null)selectedId=it.profile.id else chatPeer=it.profile.id}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=12.dp,top=16.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(selectedId!=null) IconButton(onClick={selectedId=null}){LineIcon(Symbol.BACK,Modifier.size(22.dp).semanticsLabel("返回好友列表"))}
            OneLineText(if(selectedId!=null)"个人主页" else "好友",Modifier.weight(1f),
                style=MaterialTheme.typography.headlineMedium.copy(fontWeight=FontWeight.Black))
            if(me!=null && selectedId==null) {
                if(social!=null) Box {
                    SquareKey(Symbol.FEED,"动态",Modifier.testTag("open-feed")) {timeline=true}
                    if(unreadReplies>0) UnreadDot(unreadReplies,Modifier.align(Alignment.TopEnd).offset(x=4.dp,y=(-4).dp))
                }
                PixelKey(onClick={adding=true},minHeight=38.dp,minWidth=38.dp,contentPadding=PaddingValues(0.dp),
                    modifier=Modifier.semanticsLabel("添加好友").testTag("add-friend")) {
                    LineIcon(Symbol.PLUS,Modifier.size(14.dp),LocalContentColor.current)
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("friends-list"),contentPadding=PaddingValues(bottom=20.dp)) {
            message?.takeIf{!adding}?.let{item{Text(it,Modifier.padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.primary,
                style=MaterialTheme.typography.bodyMedium)}}
            if(profile==null) item {
                SoftCard(Modifier.padding(16.dp)) {
                    Text("登录后，和好友一起进步",style=MaterialTheme.typography.titleMedium)
                    Button(onClick=onLogin){Text("去登录")}
                }
            } else if(selected!=null) item {
                var note by rememberSaveable(selected.profile.id,selected.note){mutableStateOf(selected.note)}
                SoftCard(Modifier.padding(16.dp)) {
                    ProfileIdentity(selected.profile)
                    OutlinedTextField(note,{note=it.take(40)},label={Text("好友备注（仅自己可见）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={model.saveNote(selected.profile.id,note)},enabled=!busy){Text("保存备注")}
                    TextButton(onClick={confirming=selected.profile.id to "remove"}){Text("删除好友")}
                    TextButton(onClick={confirming=selected.profile.id to "block"}){Text("拉黑")}
                }
            } else {
                val accepted=friends.filter{it.accepted}
                // Training (and resting between sets) first, then simply online.
                val active=accepted.filter{it.presence!=PresenceStatus.OFFLINE}.sortedBy{if(it.presence.training)0 else 1}
                if(active.isNotEmpty()) item(key="active") {
                    LazyRow(Modifier.fillMaxWidth().testTag("friends-active"),contentPadding=PaddingValues(horizontal=16.dp),
                        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        items(active,key={"active:"+it.profile.id}){friend->ActiveFriend(social,friend){open(friend)}}
                    }
                }
                val requests=friends.filter{!it.accepted}
                items(requests,key={"request:"+it.profile.id}){friend->RequestRow(social,friend,busy,model)}
                item(key="section") {
                    Row(Modifier.padding(start=16.dp,end=16.dp,top=12.dp,bottom=2.dp),verticalAlignment=Alignment.Bottom,
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("我的好友",style=MaterialTheme.typography.labelLarge.copy(fontWeight=FontWeight.Bold),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${accepted.size}",Modifier.testTag("friends-count"),style=pixelNumber(17.sp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(accepted.isEmpty())item{Text("还没有好友，点右上角［＋］添加。",Modifier.padding(horizontal=16.dp,vertical=8.dp),
                    color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(accepted,key={it.profile.id}){friend->
                    ConversationRow(social,friend,onAvatar={selectedId=friend.profile.id}){open(friend)}
                }
            }
        }
    }
    if(adding && me!=null) AddFriendSheet(model,social,me){adding=false}
    confirming?.let{(id,action)->
        AlertDialog(onDismissRequest={confirming=null},title={Text(if(action=="block")"拉黑此好友？" else "删除好友？")},
            text={Text("好友关系将移除，停止后续共享。")},
            confirmButton={TextButton(onClick={confirming=null;selectedId=null;model.act(id,action)},enabled=!busy){Text("确认")}},
            dismissButton={TextButton(onClick={confirming=null}){Text("取消")}})
    }
}

internal val PresenceStatus.training get()=this==PresenceStatus.TRAINING || this==PresenceStatus.REST

/** A square outline key holding one pixel icon. */
@Composable internal fun SquareKey(symbol:Symbol,label:String,modifier:Modifier=Modifier,size:Dp=38.dp,enabled:Boolean=true,onClick:()->Unit) =
    PixelOutlineKey(onClick=onClick,enabled=enabled,minHeight=size,minWidth=size,contentPadding=PaddingValues(0.dp),contentColor=MaterialTheme.colorScheme.onSurface,
        modifier=modifier.semanticsLabel(label)) {LineIcon(symbol,Modifier.size(15.dp),LocalContentColor.current)}

@Composable private fun UnreadDot(count:Int,modifier:Modifier=Modifier) {
    Text(if(count>99)"99+" else "$count",modifier.background(MaterialTheme.colorScheme.error,PixelShapeSmall).padding(horizontal=4.dp)
        .testTag("feed-unread"),style=pixelNumber(13.sp),color=MaterialTheme.colorScheme.onError)
}

/** An avatar with its presence: a small square for online, a teal ring and dumbbell corner while training. */
@Composable internal fun PresenceAvatar(social:SocialRepository?,friend:Friend,size:Dp,onClick:()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val training=friend.presence.training
    Box(Modifier.then(if(training)Modifier.border(2.dp,scheme.primary,CircleShape).padding(3.dp) else Modifier)) {
        SocialAvatar(social,friend.profile,size=size,onClick=onClick)
        if(training) Box(Modifier.align(Alignment.BottomEnd).offset(x=5.dp,y=5.dp).size(24.dp).background(scheme.background,PixelShapeSmall).padding(2.dp)
            .background(scheme.primary,PixelShapeSmall).semanticsLabel(friend.presence.label),contentAlignment=Alignment.Center) {
            LineIcon(Symbol.DUMBBELL,Modifier.size(16.dp),scheme.onPrimary)
        } else if(friend.presence!=PresenceStatus.OFFLINE) Box(Modifier.align(Alignment.BottomEnd).size(14.dp).background(scheme.background).padding(2.5.dp)
            .background(scheme.primary).semanticsLabel(friend.presence.label))
    }
}

@Composable private fun ActiveFriend(social:SocialRepository?,friend:Friend,onClick:()->Unit) {
    val scheme=MaterialTheme.colorScheme
    Column(Modifier.width(62.dp).clickable(onClick=onClick).padding(vertical=4.dp).testTag("active-${friend.profile.id}"),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
        PresenceAvatar(social,friend,48.dp,onClick)
        Text(friend.displayName,style=MaterialTheme.typography.labelSmall.copy(fontWeight=if(friend.presence.training)FontWeight.Bold else FontWeight.Normal),
            color=if(friend.presence.training)scheme.primary else scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun ConversationRow(social:SocialRepository?,friend:Friend,onAvatar:()->Unit,onOpen:()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val line=scheme.outlineVariant
    Row(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(horizontal=16.dp,vertical=10.dp).testTag("friend-row-${friend.profile.id}"),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        PresenceAvatar(social,friend,50.dp,onAvatar)
        Column(Modifier.weight(1f).drawBehind {
            drawLine(line,Offset(0f,size.height+10.dp.toPx()),Offset(size.width,size.height+10.dp.toPx()),1.dp.toPx())
        },verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(friend.displayName,Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.ExtraBold,fontSize=15.5.sp),
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(friend.presence.training) Text(friend.presence.label,Modifier.background(scheme.primaryContainer,PixelShapeSmall).padding(horizontal=5.dp),
                        style=MaterialTheme.typography.labelSmall.copy(fontWeight=FontWeight.Bold),color=scheme.primary,maxLines=1)
                }
                friend.lastMessageAt?.let{Text(ChatTimes.label(it),style=MaterialTheme.typography.labelSmall,color=scheme.onSurfaceVariant.copy(alpha=.75f),maxLines=1)}
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                val unread=friend.unread>0
                Text(friend.lastMessage ?: friend.profile.bio.ifBlank{"还没有聊天"},Modifier.weight(1f),
                    style=MaterialTheme.typography.bodyMedium.copy(fontWeight=if(unread)FontWeight.Bold else FontWeight.Normal),
                    color=if(unread)scheme.onSurface else scheme.onSurfaceVariant.copy(alpha=if(friend.lastMessage==null).7f else 1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                if(unread) Text(if(friend.unread>99)"99+" else "${friend.unread}",Modifier.background(scheme.primary,PixelShapeSmall)
                    .padding(horizontal=6.dp).testTag("unread-${friend.profile.id}"),style=pixelNumber(15.sp),color=scheme.onPrimary)
            }
        }
    }
}

@Composable private fun RequestRow(social:SocialRepository?,friend:Friend,busy:Boolean,model:FriendsViewModel) {
    val scheme=MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),shape=RoundedCornerShape(14.dp),color=scheme.surface,
        border=androidx.compose.foundation.BorderStroke(1.dp,scheme.outlineVariant)) {
        Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            SocialAvatar(social,friend.profile,size=40.dp){}
            Column(Modifier.weight(1f)) {
                OneLineText(friend.profile.nickname,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.Bold))
                Text(if(friend.incoming)"请求添加你为好友" else "等待对方接受",style=MaterialTheme.typography.labelMedium,color=scheme.onSurfaceVariant,maxLines=1)
            }
            if(friend.incoming) {
                PixelKey(onClick={model.act(friend.profile.id,"accept")},enabled=!busy,minHeight=34.dp) {Text("接受",maxLines=1)}
                SquareKey(Symbol.CLOSE,"拒绝",size=34.dp,enabled=!busy) {model.act(friend.profile.id,"reject")}
            } else PixelOutlineKey(onClick={model.act(friend.profile.id,"cancel")},enabled=!busy,minHeight=34.dp) {Text("撤回",maxLines=1)}
        }
    }
}

/** Adding a friend: paste or type a FitLog ID, see who it is, send the request; one's own ID sits below to share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AddFriendSheet(model:FriendsViewModel,social:SocialRepository?,me:SocialProfile,onClose:()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val found by model.found.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val friends by model.friends.collectAsStateWithLifecycle()
    val clipboard=androidx.compose.ui.platform.LocalClipboard.current
    val scope=rememberCoroutineScope()
    var search by rememberSaveable {mutableStateOf("")}
    var requested by remember {mutableStateOf<String?>(null)}
    // Close once the request shows up in the list.
    LaunchedEffect(friends,requested) {if(requested!=null && friends.any{it.profile.id==requested}) {requested=null;onClose()}}
    DisposableEffect(Unit){onDispose{model.found.value=null}}
    ModalBottomSheet(onDismissRequest=onClose,containerColor=scheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,bottom=24.dp).testTag("add-friend-sheet"),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("添加好友",style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Black))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search,{search=it.trim()},Modifier.weight(1f).testTag("friend-search"),singleLine=true,
                    placeholder={Text("好友的 FitLog ID")},shape=RoundedCornerShape(12.dp),
                    trailingIcon={TextButton(onClick={scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf{it.itemCount>0}?.getItemAt(0)?.text?.toString()?.trim()?.let{search=it}
                    }}){Text("粘贴")}})
                PixelKey(onClick={model.search(search)},enabled=!busy && search.isNotBlank(),minHeight=48.dp) {Text("查找",maxLines=1)}
            }
            message?.let{Text(it,color=if(found==null)scheme.error else scheme.primary,style=MaterialTheme.typography.bodyMedium)}
            found?.let{person->
                Row(Modifier.fillMaxWidth().background(scheme.surfaceVariant,RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    SocialAvatar(social,person,size=44.dp){}
                    Column(Modifier.weight(1f)) {
                        OneLineText(person.nickname,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.Bold))
                        Text(person.fitlogId,style=MaterialTheme.typography.labelSmall.copy(letterSpacing=.5.sp),color=scheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    PixelKey(onClick={requested=person.id;model.act(person.id,"request")},enabled=!busy,minHeight=38.dp) {Text("发送申请",maxLines=1)}
                }
            }
            HorizontalDivider(color=scheme.outlineVariant)
            FitLogIdLine(me.fitlogId,label="我的 FitLog ID")
        }
    }
}

@Composable internal fun ProfileIdentity(profile:SocialProfile) {
    OneLineText(profile.nickname,style=MaterialTheme.typography.titleLarge)
    FitLogIdentityRow(profile.fitlogId)
}
