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
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,contentColor=MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        val canPost=detailPost==null && (owner==null || owner==me.id)
        if(!embedded || detailPost!=null) SocialHeader(if(detailPost!=null)"动态详情" else if(owner==null) "好友动态" else "动态",::back) {
            if(detailPost==null) TextButton(onClick={launch {refresh()}},enabled=!busy){Text("↻",modifier=Modifier.semanticsLabel("刷新动态"))}
            if(canPost) PixelKey(onClick={composing=true},minHeight=34.dp,modifier=Modifier.padding(start=4.dp).testTag("compose-post")) {
                LineIcon(Symbol.PLUS,Modifier.size(12.dp),LocalContentColor.current);Text("发动态",maxLines=1)
            }
        } else if(canPost) Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.End) {
            PixelKey(onClick={composing=true},minHeight=34.dp,modifier=Modifier.testTag("compose-post")) {
                LineIcon(Symbol.PLUS,Modifier.size(12.dp),LocalContentColor.current);Text("发动态",maxLines=1)
            }
        }
        if(owner==null && detailPost==null) ReplyInboxBar(repository,unread,notices,onOpen={inbox=true},onSelect=::openNotice)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
            if(!own && detailPost==null) item {
                Text("只显示 ${SocialRules.POST_DAYS} 天内的动态",Modifier.testTag("post-window-note"),style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(posts.isEmpty() && archive.isEmpty() && detailPost==null) item {Text("还没有动态",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(detailPost?.let{listOf(it)} ?: posts,key={it.id}) {post->
                ActivityCard {
                    PostContent(repository,post,onPerson)
                    PostReactions(post,!busy,onLike={launch {
                        repository.like(post.id,!post.liked)
                        detailPost=detailPost?.let{it.copy(liked=!post.liked,likeCount=(post.likeCount+if(post.liked)-1 else 1).coerceAtLeast(0))}
                        posts=posts.map {if(it.id==post.id) it.copy(liked=!it.liked,likeCount=(it.likeCount+if(it.liked)-1 else 1).coerceAtLeast(0)) else it}
                    }},onComments={focusContext=null;commentPost=post},
                        onDelete=if(post.ownerId==me.id) ({deletePost=post}) else null)
                }
            }
            if(morePosts && detailPost==null) item {
                TextButton(onClick={launch {
                    val page=repository.feed(owner,posts.lastOrNull()?.cursor)
                    posts=(posts+page).distinctBy {it.id}.takeLast(60);morePosts=page.size==20
                    refreshArchive()
                }},enabled=!busy){Text("加载更早动态")}
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
                    ActivityCard {
                        PostContent(repository,post,onPerson)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            PixelOutlineKey(onClick={deletePost=post},minHeight=30.dp,modifier=Modifier.testTag("delete-local-${post.id}")) {
                                LineIcon(Symbol.TRASH,Modifier.size(12.dp),LocalContentColor.current);Text("删除",maxLines=1)
                            }
                        }
                    }
                }
            }
        }
    }
    }
    if(composing) AlertDialog(onDismissRequest={if(!busy) composing=false},title={Text("分享动态")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(body,{body=it.take(4000)},label={Text("今天有什么新进步？")},maxLines=6)
            Text("${images.size} / 9 张图片")
            PostImagePreviews(repository,images,!busy){path->images=images-path}
            Row {
                TextButton(onClick=picker,enabled=!busy && images.size<9){Text("添加图片")}
                if(images.isNotEmpty()) TextButton(onClick={images=emptyList()},enabled=!busy){Text("全部移除")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={launch {
        repository.publish(PostDraft(draftId,body,images));body="";images=emptyList();draftId=UUID.randomUUID().toString();composing=false;refresh()
    }},enabled=!busy && (body.isNotBlank() || images.isNotEmpty())){Text("发表")}},
        dismissButton={TextButton(onClick={composing=false},enabled=!busy){Text("稍后")}})
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

/** A post's author line, text and images. */
@Composable private fun PostContent(repository:SocialRepository,post:SocialPost,onPerson:(String)->Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
        SocialAvatar(repository,SocialProfile(post.ownerId,post.fitlogId,post.nickname,avatarPath=post.avatarPath)){onPerson(post.ownerId)}
        Column(Modifier.weight(1f).clickable {onPerson(post.ownerId)}) {
            OneLineText(post.nickname,style=MaterialTheme.typography.titleMedium)
            if(post.fitlogId.isNotBlank()) Text("ID · "+post.fitlogId,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(post.createdAt.socialTime(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if(post.body.isNotBlank()) Text(post.body,style=MaterialTheme.typography.bodyLarge)
    if(post.mediaPaths.size==1) SocialImage(repository,post.mediaPaths.single(),Modifier.fillMaxWidth().height(240.dp))
    else post.mediaPaths.chunked(3).forEach {row->
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            row.forEach {path->SocialImage(repository,path,Modifier.weight(1f).height(110.dp),gallery=post.mediaPaths)}
            repeat(3-row.size){Spacer(Modifier.weight(1f))}
        }
    }
}

/** Uploaded images are kept locally, so the preview shows exactly what will be published. */
@Composable private fun PostImagePreviews(repository:SocialRepository,images:List<String>,enabled:Boolean,onRemove:(String)->Unit) {
    images.chunked(3).forEachIndexed {rowIndex,row->
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            row.forEachIndexed {index,path->
                Box(Modifier.weight(1f).height(96.dp)) {
                    SocialImage(repository,path,Modifier.fillMaxSize(),gallery=images)
                    Surface(onClick={onRemove(path)},enabled=enabled,shape=CircleShape,
                        color=MaterialTheme.colorScheme.surface.copy(alpha=0.85f),
                        modifier=Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp)
                            .semanticsLabel("移除第 ${rowIndex*3+index+1} 张图片")) {
                        Box(contentAlignment=Alignment.Center){Text("×",style=MaterialTheme.typography.titleMedium)}
                    }
                }
            }
            repeat(3-row.size){Spacer(Modifier.weight(1f))}
        }
    }
}
