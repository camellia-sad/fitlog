package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.ChatController
import kotlinx.coroutines.*
import java.util.UUID

/**
 * One-to-one chat with large bubbles. Messages from the same side run together as a group with full rounding only at
 * its ends; a quiet time line appears between messages at least [ChatTimes.GAP] apart and tapping a bubble shows its
 * own time. Long-press opens a small bar of actions above the bubble. The header names what the friend is training.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(repository:SocialRepository,me:SocialProfile,friend:Friend,friends:List<Friend>,onBack:()->Unit,onPerson:(String)->Unit) {
    val scheme=MaterialTheme.colorScheme
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
    var more by remember {mutableStateOf(false)}
    var confirming by remember {mutableStateOf<String?>(null)}
    var selected by remember {mutableStateOf<Set<String>>(emptySet())}
    var selecting by remember {mutableStateOf(false)}
    var forwarding by remember {mutableStateOf(false)}
    var forwardRequest by remember {mutableStateOf(UUID.randomUUID().toString())}
    var locate by remember {mutableStateOf<String?>(null)}
    var highlight by remember {mutableStateOf<String?>(null)}
    var shownTime by remember {mutableStateOf<String?>(null)}
    var person by remember(friend.profile.id) {mutableStateOf<SocialPerson?>(null)}
    val list=rememberLazyListState()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {while(isActive) {controller.refresh();delay(5_000)}}
            finally {controller.release()}
        }
    }
    // What the friend is doing right now, for the header; shared live details name the exercise.
    LaunchedEffect(friend.profile.id,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive) {
                try {person=repository.person(friend.profile.id)} catch(e:CancellationException) {throw e} catch(_:Exception) {}
                delay(10_000)
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {scope.coroutineContext.cancelChildren();controller.release();reply=null;menu=null;selected=emptySet();selecting=false}
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    val picker=rememberImagePicker(onImage={jpeg->
        val path=repository.uploadChatImage(jpeg)
        controller.send(MessageDraft(body=text,mediaPath=path,replyId=reply?.id)) {text="";reply=null}
    },onError={controller.error.value=it})
    BackHandler {if(selecting) {selecting=false;selected=emptySet()} else onBack()}
    LaunchedEffect(locate,messages,busy,error) {
        if(error!=null) {locate=null;return@LaunchedEffect}
        val id=locate ?: return@LaunchedEffect
        val index=messages.indexOfFirst {it.id==id}
        if(index>=0) {list.animateScrollToItem(index+1);highlight=id;locate=null;delay(1_600);if(highlight==id)highlight=null}
        else if(!busy) {controller.locate(id)}
    }
    // Older messages load on their own when the top of the list comes into view.
    LaunchedEffect(list,hasOlder) {
        snapshotFlow {list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount}.collect {(last,total)->
            // Only a full page can have more behind it; loading switches to browsing, which pauses live refresh.
            if(last!=null && total>2 && last>=total-2 && hasOlder && !controller.busy.value && controller.messages.value.size>=SocialRules.MESSAGE_PAGE) controller.older()
        }
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("chat-screen")) {
        if(selecting) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            PixelOutlineKey(onClick={selecting=false;selected=emptySet()},minHeight=36.dp) {Text("取消",maxLines=1)}
            Text("已选 ${selected.size} 条",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Black))
            PixelKey(onClick={forwarding=true;forwardRequest=UUID.randomUUID().toString()},enabled=selected.isNotEmpty(),minHeight=36.dp,
                modifier=Modifier.testTag("forward-selected")) {
                LineIcon(Symbol.FORWARD,Modifier.size(13.dp),LocalContentColor.current);Text("转发",maxLines=1)
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            SquareKey(Symbol.BACK,"返回",onClick=onBack)
            SocialAvatar(repository,friend.profile,size=36.dp) {onPerson(friend.profile.id)}
            Column(Modifier.weight(1f)) {
                Text(friend.displayName,style=MaterialTheme.typography.titleMedium.copy(fontWeight=FontWeight.Black),maxLines=1,overflow=TextOverflow.Ellipsis)
                val status=person?.statusLine() ?: friend.presence.label
                Text(status,Modifier.basicMarquee(iterations=Int.MAX_VALUE).testTag("chat-status"),
                    style=MaterialTheme.typography.labelMedium.copy(fontWeight=FontWeight.Bold),
                    color=if((person?.status ?: friend.presence)==PresenceStatus.OFFLINE) scheme.onSurfaceVariant else scheme.primary,maxLines=1)
            }
            Box {
                SquareKey(Symbol.MORE,"更多",Modifier.testTag("chat-more")) {more=true}
                DropdownMenu(more,{more=false}) {
                    DropdownMenuItem(text={Text("查看主页")},onClick={more=false;onPerson(friend.profile.id)})
                    DropdownMenuItem(text={Text("清空聊天记录",color=scheme.error)},onClick={more=false;confirming="clear"})
                }
            }
        }
        HorizontalDivider(color=scheme.outlineVariant)
        error?.let {Text(it,Modifier.padding(horizontal=16.dp,vertical=4.dp),color=scheme.error,style=MaterialTheme.typography.bodySmall)}
        if(browsing) TextButton(onClick=controller::latest,Modifier.align(Alignment.CenterHorizontally)) {Text("回到最新消息")}
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widest=maxWidth*.8f
            LazyColumn(Modifier.fillMaxSize(),state=list,reverseLayout=true,contentPadding=PaddingValues(start=12.dp,end=12.dp,top=8.dp,bottom=10.dp)) {
                items(pending,key={"pending:"+it.id}) {draft->
                    Column(Modifier.fillMaxWidth().padding(top=3.dp),horizontalAlignment=Alignment.End) {
                        Box(Modifier.alpha(.55f)) {
                            Bubble(true,top=true,bottom=true,widest=widest) {
                                Text(draft.body.ifBlank {"[图片]"},style=BubbleText,color=scheme.onPrimary)
                            }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("未发送",style=MaterialTheme.typography.labelSmall,color=scheme.error)
                            TextButton(onClick={controller.retry(draft)},enabled=!busy,contentPadding=PaddingValues(horizontal=6.dp)) {Text("重试",color=scheme.error)}
                            TextButton(onClick={controller.discard(draft)},enabled=!busy,contentPadding=PaddingValues(horizontal=6.dp)) {Text("删除",color=scheme.error)}
                        }
                    }
                }
                itemsIndexed(messages,key={_,it->it.id}) {index,message->
                    val mine=message.sender==me.id
                    val earlier=messages.getOrNull(index+1)
                    val later=messages.getOrNull(index-1)
                    val separated=earlier==null || ChatTimes.separated(earlier.createdAt,message.createdAt)
                    val laterSeparated=later==null || ChatTimes.separated(message.createdAt,later.createdAt)
                    val top=separated || earlier?.sender!=message.sender || earlier?.withdrawn==true
                    val bottom=laterSeparated || later?.sender!=message.sender || later?.withdrawn==true
                    Column(Modifier.fillMaxWidth().padding(top=if(separated) 0.dp else if(top) 10.dp else 2.dp)) {
                        if(separated) Text(ChatTimes.label(message.createdAt),Modifier.align(Alignment.CenterHorizontally).padding(top=14.dp,bottom=8.dp),
                            style=MaterialTheme.typography.labelSmall,color=scheme.onSurfaceVariant.copy(alpha=.7f))
                        if(message.withdrawn) {
                            Text(if(mine)"你撤回了一条消息" else "${friend.displayName}撤回了一条消息",
                                Modifier.align(Alignment.CenterHorizontally).combinedClickable(onClick={},onLongClick={menu=message}).padding(vertical=4.dp),
                                style=MaterialTheme.typography.labelSmall,color=scheme.onSurfaceVariant.copy(alpha=.75f))
                            if(menu?.id==message.id) MessageActions(mine,message,onDismiss={menu=null},onAction={menu=null;if(it=="删除")controller.delete(message)})
                        } else {
                        val chosen=message.id in selected
                        val toggle={
                            if(message.forwarded.isNotEmpty()) controller.error.value="聊天记录请单独转发"
                            else selected=if(chosen) selected-message.id else if(selected.size<SocialRules.MAX_FORWARD) selected+message.id else selected
                        }
                        Row(Modifier.fillMaxWidth().then(if(selecting) Modifier.clickable(onClick=toggle) else Modifier),verticalAlignment=Alignment.CenterVertically) {
                            if(selecting) Box(Modifier.padding(end=8.dp).size(20.dp).then(if(chosen) Modifier.background(scheme.primary,PixelShapeSmall)
                                else Modifier.border(1.5.dp,scheme.outline,PixelShapeSmall)).testTag("select-${message.id}"),contentAlignment=Alignment.Center) {
                                if(chosen) LineIcon(Symbol.CHECK,Modifier.size(11.dp),scheme.onPrimary)
                            }
                            Column(Modifier.weight(1f),horizontalAlignment=if(mine) Alignment.End else Alignment.Start) {
                                Box {
                                    Bubble(mine,top,bottom,widest,highlight=highlight==message.id,
                                        modifier=Modifier.combinedClickable(onClick={
                                            if(selecting) toggle() else shownTime=if(shownTime==message.id) null else message.id
                                        },onLongClick={if(!selecting) menu=message}).testTag("bubble-${message.id}"),
                                        image=message.mediaPath!=null && message.body.isBlank() && message.replyId==null && message.forwarded.isEmpty()) {
                                        MessageContent(repository,message,mine,messages,widest,onLocate={locate=it},onMenu={menu=message})
                                    }
                                    if(menu?.id==message.id) MessageActions(mine,message,onDismiss={menu=null},onAction={action->
                                        menu=null
                                        when(action) {
                                            "引用"->reply=message
                                            "转发"->{selected=setOf(message.id);forwarding=true;forwardRequest=UUID.randomUUID().toString()}
                                            "多选"->{selecting=true;selected=setOf(message.id)}
                                            "撤回"->controller.withdraw(message)
                                            "删除"->controller.delete(message)
                                        }
                                    })
                                }
                                if(shownTime==message.id) Text(ChatTimes.label(message.createdAt),Modifier.padding(horizontal=6.dp,vertical=2.dp),
                                    style=MaterialTheme.typography.labelSmall.copy(fontSize=10.5.sp),color=scheme.onSurfaceVariant.copy(alpha=.7f))
                            }
                        }
                        }
                    }
                }
                item(key="older") {
                    Box(Modifier.fillMaxWidth().padding(vertical=10.dp),contentAlignment=Alignment.Center) {
                        if(messages.size<SocialRules.MESSAGE_PAGE) Unit
                        else if(hasOlder) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        else Text("没有更早的消息",style=MaterialTheme.typography.labelSmall,color=scheme.onSurfaceVariant.copy(alpha=.6f))
                    }
                }
            }
        }
        reply?.let {source->
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).padding(bottom=6.dp).background(scheme.surfaceVariant,RoundedCornerShape(12.dp))
                .padding(start=12.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                LineIcon(Symbol.REPLY,Modifier.size(12.dp),scheme.primary)
                Text("回复 "+(if(source.sender==me.id)"自己" else friend.displayName)+"："+source.body.ifBlank {"[图片或记录]"},Modifier.weight(1f),
                    style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                SquareKey(Symbol.CLOSE,"取消引用",size=28.dp) {reply=null}
            }
        }
        HorizontalDivider(color=scheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp,bottom=10.dp).testTag("chat-composer"),
            verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SquareKey(Symbol.IMAGE,"发送图片",Modifier.padding(bottom=3.dp),size=42.dp,enabled=!busy,onClick=picker)
            OutlinedTextField(text,{text=it.take(8000)},Modifier.weight(1f).heightIn(min=46.dp).testTag("chat-input"),maxLines=4,
                placeholder={Text("发消息")},shape=RoundedCornerShape(23.dp),textStyle=MaterialTheme.typography.bodyLarge)
            PixelKey(onClick={controller.send(MessageDraft(body=text,replyId=reply?.id)) {text="";reply=null}},enabled=!busy && text.isNotBlank(),
                minHeight=42.dp,minWidth=42.dp,contentPadding=PaddingValues(0.dp),modifier=Modifier.padding(bottom=3.dp).semanticsLabel("发送")) {
                LineIcon(Symbol.SEND,Modifier.size(15.dp),LocalContentColor.current)
            }
        }
    }
    if(forwarding) AlertDialog(onDismissRequest={forwarding=false},title={Text("转发给好友")},text={
        LazyColumn {
            items(friends.filter {it.accepted},key={it.profile.id}) {target->
                Row(Modifier.fillMaxWidth().clickable(enabled=!busy) {controller.action {
                    repository.forward(target.profile.id,messages.filter {it.id in selected}.sortedBy {it.createdAt}.map {it.id},forwardRequest)
                    forwarding=false;selected=emptySet();selecting=false
                }}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    SocialAvatar(repository,target.profile,size=36.dp) {}
                    Text(target.displayName,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    },confirmButton={TextButton(onClick={forwarding=false}){Text("取消")}})
    if(confirming=="clear") AlertDialog(onDismissRequest={confirming=null},title={Text("清空聊天记录？")},
        text={Text("清空本机聊天记录，对方的记录保留。已上传的旧备份仍可能包含这些记录，可在云端备份页删除。")},
        confirmButton={TextButton(onClick={controller.clear();confirming=null}){Text("清空")}},
        dismissButton={TextButton(onClick={confirming=null}){Text("取消")}})
}

private val BubbleText @Composable get()=MaterialTheme.typography.bodyLarge.copy(fontSize=15.5.sp,lineHeight=23.sp)

/** Teal for one's own, a light outlined bubble for the friend's; the corners facing the rest of the group stay small. */
@Composable private fun Bubble(mine:Boolean,top:Boolean,bottom:Boolean,widest:androidx.compose.ui.unit.Dp,modifier:Modifier=Modifier,
    highlight:Boolean=false,image:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val big=20.dp; val small=6.dp
    val shape=if(mine) RoundedCornerShape(big,if(top)big else small,if(bottom)big else small,big)
        else RoundedCornerShape(if(top)big else small,big,big,if(bottom)big else small)
    Column(Modifier.widthIn(max=widest).clip(shape).background(if(mine)scheme.primary else scheme.surface)
        .then(if(highlight) Modifier.border(2.dp,fitColors.orange,shape) else if(!mine) Modifier.border(1.dp,scheme.outlineVariant,shape) else Modifier)
        .then(modifier).padding(if(image) PaddingValues(0.dp) else PaddingValues(horizontal=13.dp,vertical=9.dp)),
        verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
}

@Composable private fun MessageContent(repository:SocialRepository,message:ChatMessage,mine:Boolean,messages:List<ChatMessage>,widest:androidx.compose.ui.unit.Dp,
    onLocate:(String)->Unit,onMenu:()->Unit) {
    val scheme=MaterialTheme.colorScheme
    val ink=if(mine)scheme.onPrimary else scheme.onSurface
    message.replyId?.let {original->
        val source=messages.find {it.id==original}
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if(mine)scheme.onPrimary.copy(alpha=.16f) else scheme.surfaceVariant)
            .combinedClickable(onClick={onLocate(original)},onLongClick=onMenu).height(IntrinsicSize.Min).padding(horizontal=9.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(ink.copy(alpha=.5f),RoundedCornerShape(2.dp)))
            Text(if(source?.withdrawn==true)"原消息已撤回" else message.replyPreview.orEmpty(),style=MaterialTheme.typography.bodySmall,
                color=ink.copy(alpha=.85f),maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
    if(message.body.isNotBlank()) Text(message.body,style=BubbleText,color=ink)
    val alone=message.body.isBlank() && message.replyId==null && message.forwarded.isEmpty()
    message.mediaPath?.let {ChatPicture(repository,it,alone,minOf(widest,240.dp),onMenu)}
    if(message.forwarded.isNotEmpty()) Column(Modifier.widthIn(min=180.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text("聊天记录",style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.Bold),color=ink)
        message.forwarded.take(4).forEach {part->
            Text(part.sender+"："+part.body.ifBlank {"[图片]"},style=MaterialTheme.typography.bodySmall,color=ink.copy(alpha=.8f),maxLines=2,overflow=TextOverflow.Ellipsis)
            part.mediaPath?.let {SocialImage(repository,it,Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)),onLongClick=onMenu)}
        }
        HorizontalDivider(Modifier.padding(top=4.dp),color=ink.copy(alpha=.2f))
        Text("聊天记录 · ${message.forwarded.size} 条",style=MaterialTheme.typography.labelSmall,color=ink.copy(alpha=.65f))
    }
}

/** Picture shapes already seen, so a picture shown again gets its frame at once instead of after loading. */
private object ChatImageRatios {
    private val ratios=android.util.LruCache<String,Float>(500)
    fun get(path:String):Float?=ratios.get(path)
    fun put(path:String,ratio:Float){ratios.put(path,ratio)}
}

/**
 * A picture framed to its own shape ([ChatImageFrame]): portrait pictures get a narrow, tall frame and landscape ones
 * a wide, short one, so the bubble hugs the picture. Alone in a bubble it fills it edge to edge; beside text or a quote
 * it has its own rounded corners. Until its shape is known it holds a square.
 */
@Composable private fun ChatPicture(repository:SocialRepository,path:String,alone:Boolean,maxWidth:androidx.compose.ui.unit.Dp,onMenu:()->Unit) {
    var ratio by remember(path) {mutableStateOf(ChatImageRatios.get(path))}
    val (width,height)=ChatImageFrame.size(ratio ?: 1f,maxWidth.value,320f)
    SocialImage(repository,path,Modifier.size(width.dp,height.dp).then(if(alone) Modifier else Modifier.clip(RoundedCornerShape(16.dp)))
        .background(MaterialTheme.colorScheme.surfaceVariant).testTag("chat-picture"),
        contentScale=androidx.compose.ui.layout.ContentScale.Crop,onLongClick=onMenu,onAspect={value->
            if(ChatImageRatios.get(path)!=value) ChatImageRatios.put(path,value)
            ratio=value
        })
}

/** A dark bar of actions just above the long-pressed bubble (below it when there is no room). */
@Composable private fun MessageActions(mine:Boolean,message:ChatMessage,onDismiss:()->Unit,onAction:(String)->Unit) {
    val density=androidx.compose.ui.platform.LocalDensity.current
    val gap=with(density) {8.dp.roundToPx()}
    val actions=buildList {
        if(!message.withdrawn) {
            add(Symbol.REPLY to "引用"); add(Symbol.FORWARD to "转发")
            if(message.forwarded.isEmpty()) add(Symbol.SELECT to "多选")
            if(mine) add(Symbol.UNDO to "撤回")
        }
        add(Symbol.TRASH to "删除")
    }
    Popup(popupPositionProvider=AboveOrBelow(gap,mine),onDismissRequest=onDismiss,properties=PopupProperties(focusable=true)) {
        Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.inverseSurface,shadowElevation=8.dp,modifier=Modifier.testTag("message-actions")) {
            Row(Modifier.padding(4.dp)) {
                actions.forEach {(symbol,label)->
                    val tint=if(label=="删除") Color(0xFFFF8A80) else MaterialTheme.colorScheme.inverseOnSurface
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable {onAction(label)}.padding(horizontal=10.dp,vertical=6.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        LineIcon(symbol,Modifier.size(15.dp),tint)
                        Text(label,style=MaterialTheme.typography.labelSmall,color=tint,maxLines=1)
                    }
                }
            }
        }
    }
}

private class AboveOrBelow(private val gap:Int,private val alignEnd:Boolean):PopupPositionProvider {
    override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
        val x=(if(alignEnd) anchorBounds.right-popupContentSize.width else anchorBounds.left).coerceIn(0,maxOf(0,windowSize.width-popupContentSize.width))
        val above=anchorBounds.top-popupContentSize.height-gap
        return IntOffset(x,if(above>=0) above else anchorBounds.bottom+gap)
    }
}
