package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialTimelineScreen(repository:SocialRepository,me:SocialProfile,owner:String?=null,
    onBack:()->Unit,onPerson:(String)->Unit,embedded:Boolean=false) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var posts by remember {mutableStateOf<List<SocialPost>>(emptyList())}
    var morePosts by remember {mutableStateOf(true)}
    // One's own page also lists the posts kept only on this device (older than the cloud keeps, or all of them offline).
    val own=owner!=null && owner==me.id
    var archive by remember {mutableStateOf<List<SocialPost>>(emptyList())}
    var offline by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var composing by rememberSaveable {mutableStateOf(false)}
    var body by rememberSaveable {mutableStateOf("")}
    var draftId by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}
    var images by rememberSaveable {mutableStateOf<List<String>>(emptyList())}
    var notices by remember {mutableStateOf<List<ReplyNotice>>(emptyList())}
    var unread by remember {mutableStateOf(0)}
    var inbox by rememberSaveable {mutableStateOf(false)}
    var moreNotices by remember {mutableStateOf(false)}
    var detailPost by remember {mutableStateOf<SocialPost?>(null)}
    var focusContext by remember {mutableStateOf<CommentContext?>(null)}
    suspend fun refreshInbox() {
        if(owner==null){unread=repository.unreadReplies();notices=repository.replyNotices();moreNotices=notices.size==20}
    }
    var commentPost by remember {mutableStateOf<SocialPost?>(null)}
    var deletePost by remember {mutableStateOf<SocialPost?>(null)}
    fun launch(block:suspend ()->Unit) {
        if(busy) return
        scope.launch {
            busy=true;error=null
            try {block()} catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message ?: "加载失败，请重试"}
            finally {busy=false}
        }
    }
    suspend fun refreshArchive() {
        if(!own) return
        val cutoff=Instant.now().minus(java.time.Duration.ofDays(SocialRules.POST_DAYS))
        val shown=posts.map {it.id}.toSet()
        archive=repository.ownArchive().filter {it.id !in shown && (offline || it.createdAt.isBefore(cutoff))}
    }
    suspend fun refresh() {
        refreshInbox()
        try {posts=repository.feed(owner);morePosts=posts.size==20;offline=false}
        catch(e:CancellationException){throw e}
        catch(e:Exception){if(!own) throw e;offline=true;posts=emptyList();morePosts=false}
        refreshArchive()
    }
    LaunchedEffect(repository,me.id,owner,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                refresh()
                // Stay alive while shown: leaving this block runs the cleanup below and would empty the list.
                if(owner==null) while(isActive) {delay(15_000);try {refreshInbox()} catch(e:CancellationException){throw e} catch(_:Exception){}}
                else awaitCancellation()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message ?: "加载失败，请刷新"}
            finally {posts=emptyList();archive=emptyList();commentPost=null;notices=emptyList();focusContext=null}
        }
    }
    // While the friends' feed is on screen its posts and replies do not pop up as notifications.
    if(owner==null) LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            dev.lzk.fitlog.features.SocialViewing.feed.value=true
            try {awaitCancellation()} finally {dev.lzk.fitlog.features.SocialViewing.feed.value=false}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {scope.coroutineContext.cancelChildren();posts=emptyList();archive=emptyList();commentPost=null;notices=emptyList();focusContext=null}
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    val picker=rememberImagePicker(onImage={jpeg->
        if(images.size<9) {
            busy=true
            try {images=images+repository.uploadImage(jpeg)} finally {busy=false}
        }
    },onError={error=it})
    fun openNotice(n:ReplyNotice) {launch {
        val context=repository.commentContext(n.comment.id)
        focusContext=context;detailPost=context.post;commentPost=context.post;inbox=false
    }}
    fun back() {if(inbox)inbox=false else if(detailPost!=null)detailPost=null else onBack()}
    BackHandler {back()}
    if(inbox) {
        ReplyMessagesScreen(repository,unread,notices,busy,error,onBack={inbox=false},
            onReadAll={launch {repository.readAllReplies();refreshInbox()}},
            onMore={launch {
                val page=repository.replyNotices(notices.lastOrNull()?.comment?.cursor)
                notices=(notices+page).distinctBy{it.comment.id}.takeLast(100);moreNotices=page.size==20
            }},more=moreNotices,onSelect=::openNotice)
        return
    }
    var refreshing by remember {mutableStateOf(false)}
    val list=androidx.compose.foundation.lazy.rememberLazyListState()
    fun like(post:SocialPost)=launch {
        repository.like(post.id,!post.liked)
        detailPost=detailPost?.let{it.copy(liked=!post.liked,likeCount=(post.likeCount+if(post.liked)-1 else 1).coerceAtLeast(0))}
        posts=posts.map {if(it.id==post.id) it.copy(liked=!it.liked,likeCount=(it.likeCount+if(it.liked)-1 else 1).coerceAtLeast(0)) else it}
    }
    fun older()=launch {
        val page=repository.feed(owner,posts.lastOrNull()?.cursor)
        posts=(posts+page).distinctBy {it.id}.takeLast(60);morePosts=page.size==20
        refreshArchive()
    }
    // Older posts follow on their own as the end of the list comes into view.
    LaunchedEffect(list,morePosts,detailPost) {
        snapshotFlow {list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount}.collect {(last,total)->
            if(last!=null && total>0 && last>=total-2 && morePosts && detailPost==null && posts.isNotEmpty() && !busy) older()
        }
    }
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,contentColor=MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize()) {
        val canPost=detailPost==null && (owner==null || owner==me.id)
        if(!embedded || detailPost!=null) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            SquareKey(Symbol.BACK,"返回",onClick=::back)
            Text(if(detailPost!=null)"动态详情" else if(owner==null) "好友动态" else "动态",Modifier.weight(1f),
                style=MaterialTheme.typography.titleLarge.copy(fontWeight=androidx.compose.ui.text.font.FontWeight.Black),maxLines=1)
            if(canPost) PixelKey(onClick={composing=true},minHeight=38.dp,modifier=Modifier.testTag("compose-post")) {
                LineIcon(Symbol.PLUS,Modifier.size(12.dp),LocalContentColor.current);Text("发动态",maxLines=1)
            }
        } else if(canPost) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(top=10.dp),horizontalArrangement=Arrangement.End) {
            PixelKey(onClick={composing=true},minHeight=34.dp,modifier=Modifier.testTag("compose-post")) {
                LineIcon(Symbol.PLUS,Modifier.size(12.dp),LocalContentColor.current);Text("发动态",maxLines=1)
            }
        }
        error?.let {Text(it,Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.error)}
        val pull=androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(refreshing,onRefresh={
            if(!busy) scope.launch {refreshing=true;try {refresh();error=null} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message} finally {refreshing=false}}
        },Modifier.weight(1f).fillMaxWidth(),state=pull,indicator={
            PixelRefresh(refreshing || pull.distanceFraction>0f,pull.distanceFraction,Modifier.align(Alignment.TopCenter))
        }) {
        LazyColumn(Modifier.fillMaxSize().testTag("feed-list"),state=list,verticalArrangement=Arrangement.spacedBy(12.dp),
            contentPadding=PaddingValues(start=12.dp,end=12.dp,top=4.dp,bottom=20.dp)) {
            if(owner==null && detailPost==null && unread>0) item(key="inbox") {ReplyInboxBar(repository,unread,notices,onOpen={inbox=true},onSelect=::openNotice)}
            if(!own && detailPost==null) item(key="window") {
                Text("只显示 ${SocialRules.POST_DAYS} 天内的动态",Modifier.padding(horizontal=4.dp).testTag("post-window-note"),style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.75f))
            }
            if(posts.isEmpty() && archive.isEmpty() && detailPost==null && !busy) item {Text("还没有动态",Modifier.padding(4.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(detailPost?.let{listOf(it)} ?: posts,key={it.id}) {post->
                PostCard(repository,post,me,!busy,onPerson,onLike={like(post)},onComments={focusContext=null;commentPost=post},
                    onDelete=if(post.ownerId==me.id) ({deletePost=post}) else null)
            }
            if(morePosts && detailPost==null && posts.isNotEmpty()) item(key="older") {
                Box(Modifier.fillMaxWidth().padding(vertical=8.dp),contentAlignment=Alignment.Center) {PixelRefresh(true,1f)}
            }
            if(own && detailPost==null && !morePosts && archive.isNotEmpty()) {
                item(key="archive-divider") {
                    Row(Modifier.fillMaxWidth().padding(top=6.dp).testTag("local-archive"),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outlineVariant)
                        Text(if(offline) "仅本机" else "${SocialRules.POST_DAYS} 天前 · 仅本机",style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f),color=MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                items(archive,key={"local:"+it.id}) {post->
                    PostCard(repository,post,me,!busy,onPerson,onLike=null,onComments=null,onDelete={deletePost=post})
                }
            }
        }
        }
    }
    }
    if(composing) PostComposer(repository,images,body,busy,error,onBody={body=it.take(4000)},onPick=picker,
        onRemove={path->images=images-path},onCancel={composing=false}) {
        launch {
            repository.publish(PostDraft(draftId,body,images));body="";images=emptyList();draftId=UUID.randomUUID().toString();composing=false;refresh()
        }
    }
    commentPost?.let {post-> CommentsDialog(repository,me,post,onPerson,onClose={commentPost=null;focusContext=null},focusContext=focusContext,onViewed={id->
        launch {repository.readReply(id);refreshInbox()}
    },onChanged={delta->
        detailPost=detailPost?.let{it.copy(commentCount=(it.commentCount+delta).coerceAtLeast(0))}
        commentPost=commentPost?.let{it.copy(commentCount=(it.commentCount+delta).coerceAtLeast(0))}
        launch {posts=repository.feed(owner)}
    })}
    deletePost?.let {post->
        AlertDialog(onDismissRequest={deletePost=null},title={Text("删除这条动态？")},
            confirmButton={TextButton(onClick={launch {
                // A post the cloud no longer holds (or one shown offline) is removed from this device only.
                repository.deleteOwnPost(post.id,inCloud=posts.any {it.id==post.id})
                posts=posts.filterNot {it.id==post.id};archive=archive.filterNot {it.id==post.id};deletePost=null
            }}){Text("删除")}},
            dismissButton={TextButton(onClick={deletePost=null}){Text("取消")}})
    }
}
