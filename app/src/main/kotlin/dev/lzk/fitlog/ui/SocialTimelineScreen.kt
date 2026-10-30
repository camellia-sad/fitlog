package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    friend:Friend?=null,onBack:()->Unit,onPerson:(String)->Unit,
    onNote:(String)->Unit={},onRemove:()->Unit={},onBlock:()->Unit={}) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var posts by remember {mutableStateOf<List<SocialPost>>(emptyList())}
    var person by remember {mutableStateOf<SocialPerson?>(null)}
    var history by remember {mutableStateOf<List<SharedWorkout>>(emptyList())}
    var morePosts by remember {mutableStateOf(true)}
    var moreHistory by remember {mutableStateOf(true)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var composing by rememberSaveable {mutableStateOf(false)}
    var body by rememberSaveable {mutableStateOf("")}
    var draftId by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}
    var images by rememberSaveable {mutableStateOf<List<String>>(emptyList())}
    var commentPost by remember {mutableStateOf<SocialPost?>(null)}
    var note by rememberSaveable(friend?.profile?.id) {mutableStateOf(friend?.note.orEmpty())}
    var confirm by remember {mutableStateOf<String?>(null)}
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
    suspend fun refresh() {
        posts=repository.feed(owner);morePosts=posts.size==20
        if(owner!=null) {
            person=repository.person(owner)
            history=repository.sharedHistory(owner);moreHistory=history.size==20
        }
    }
    LaunchedEffect(repository,me.id,owner,lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                refresh()
                if(owner!=null) while(isActive) {delay(15_000);person=repository.person(owner)}
                else awaitCancellation()
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message ?: "加载失败，请刷新"}
            finally {posts=emptyList();history=emptyList();person=null;commentPost=null}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {scope.coroutineContext.cancelChildren();posts=emptyList();history=emptyList();person=null;commentPost=null}
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
    BackHandler(onBack=onBack)
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        SocialHeader(if(owner==null) "好友动态" else "个人主页",onBack) {
            TextButton(onClick={launch {refresh()}},enabled=!busy){Text("刷新")}
            if(owner==null || owner==me.id) TextButton(onClick={composing=true}){Text("发表")}
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=12.dp)) {
            person?.let {current->
                item {
                    SoftCard {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            SocialAvatar(repository,current.profile){}
                            Column {
                                OneLineText(current.profile.nickname,style=MaterialTheme.typography.titleLarge)
                                androidx.compose.foundation.text.selection.SelectionContainer {Text("FitLog ID："+current.profile.fitlogId,style=MaterialTheme.typography.bodySmall)}
                                Text(current.status.label,color=MaterialTheme.colorScheme.primary)
                            }
                        }
                        if(current.profile.bio.isNotBlank()) Text(current.profile.bio)
                        current.live?.let {live-> LiveTrainingCard(live,current.status)}
                        if(friend!=null) {
                            OutlinedTextField(note,{note=it.take(40)},label={Text("备注（仅自己可见）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            Row {
                                TextButton(onClick={onNote(note)}){Text("保存备注")}
                                TextButton(onClick={confirm="remove"}){Text("删除好友")}
                                TextButton(onClick={confirm="block"}){Text("拉黑")}
                            }
                        }
                    }
                }
                item {Text("训练历史",style=MaterialTheme.typography.titleMedium)}
                if(history.isEmpty()) item {Text(if(current.sharing.history) "暂无共享训练" else "尚未开启训练历史共享",style=MaterialTheme.typography.bodySmall)}
                items(history,key={"history:"+it.id}) {workout->
                    SoftCard {
                        OneLineText(workout.label+" · "+workout.status)
                        Text(workout.startedAt.socialTime()+" · "+workout.durationSeconds/60+" 分钟",style=MaterialTheme.typography.bodySmall)
                        Text("${workout.completedSets} / ${workout.totalSets} 组")
                        workout.exercises.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
                    }
                }
                if(moreHistory) item {
                    TextButton(onClick={launch {
                        val last=history.lastOrNull()
                        val page=repository.sharedHistory(requireNotNull(owner),last?.let {SocialCursor(it.startedAt,it.id)})
                        history=(history+page).distinctBy {it.id}.takeLast(60);moreHistory=page.size==20
                    }},enabled=!busy){Text("更早训练")}
                }
                item {Text("动态",style=MaterialTheme.typography.titleMedium)}
            }
            if(posts.isEmpty()) item {Text("还没有动态，记录今天的小小进步。",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(posts,key={it.id}) {post->
                SoftCard {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        SocialAvatar(repository,SocialProfile(post.ownerId,"",post.nickname,avatarPath=post.avatarPath)){onPerson(post.ownerId)}
                        Column(Modifier.weight(1f)) {
                            OneLineText(post.nickname,style=MaterialTheme.typography.titleMedium)
                            Text(post.createdAt.socialTime(),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    if(post.body.isNotBlank()) Text(post.body)
                    post.mediaPaths.chunked(3).forEach {row->
                        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            row.forEach {path->SocialImage(repository,path,Modifier.weight(1f).height(110.dp))}
                            repeat(3-row.size){Spacer(Modifier.weight(1f))}
                        }
                    }
                    Row {
                        TextButton(onClick={launch {repository.like(post.id,!post.liked)
                            posts=posts.map {if(it.id==post.id) it.copy(liked=!it.liked,likeCount=(it.likeCount+if(it.liked)-1 else 1).coerceAtLeast(0)) else it}
                        }},enabled=!busy){Text((if(post.liked)"已赞 " else "赞 ")+post.likeCount)}
                        TextButton(onClick={commentPost=post}){Text("评论 "+post.commentCount)}
                        if(post.ownerId==me.id) TextButton(onClick={deletePost=post}){Text("删除")}
                    }
                }
            }
            if(morePosts) item {
                TextButton(onClick={launch {
                    val page=repository.feed(owner,posts.lastOrNull()?.cursor)
                    posts=(posts+page).distinctBy {it.id}.takeLast(60);morePosts=page.size==20
                }},enabled=!busy){Text("加载更早动态")}
            }
        }
    }
    if(composing) AlertDialog(onDismissRequest={if(!busy) composing=false},title={Text("分享动态")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(body,{body=it.take(4000)},label={Text("今天有什么新进步？")},maxLines=6)
            Text("${images.size} / 9 张图片")
            Row {
                TextButton(onClick=picker,enabled=!busy && images.size<9){Text("添加图片")}
                TextButton(onClick={images=emptyList()},enabled=!busy){Text("移除图片")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={launch {
        repository.publish(PostDraft(draftId,body,images));body="";images=emptyList();draftId=UUID.randomUUID().toString();composing=false;refresh()
    }},enabled=!busy && (body.isNotBlank() || images.isNotEmpty())){Text("发表")}},
        dismissButton={TextButton(onClick={composing=false},enabled=!busy){Text("稍后")}})
    commentPost?.let {post-> CommentsDialog(repository,me,post,onPerson,onClose={commentPost=null},onChanged={
        launch {posts=repository.feed(owner)}
    })}
    deletePost?.let {post->
        AlertDialog(onDismissRequest={deletePost=null},title={Text("删除这条动态？")},
            confirmButton={TextButton(onClick={launch {repository.deletePost(post.id);posts=posts.filterNot {it.id==post.id};deletePost=null}}){Text("删除")}},
            dismissButton={TextButton(onClick={deletePost=null}){Text("取消")}})
    }
    confirm?.let {action->
        AlertDialog(onDismissRequest={confirm=null},title={Text(if(action=="block") "拉黑好友？" else "删除好友？")},
            text={Text("停止后续共享与新消息。")},
            confirmButton={TextButton(onClick={confirm=null;if(action=="block")onBlock() else onRemove()}){Text("确认")}},
            dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})
    }
}

@Composable private fun LiveTrainingCard(live:LiveTraining,status:PresenceStatus) {
    var now by remember {mutableStateOf(Instant.now())}
    LaunchedEffect(live.restEndsAt) {while(isActive){now=Instant.now();delay(1_000)}}
    HorizontalDivider()
    OneLineText(live.label+" · "+status.label,style=MaterialTheme.typography.titleMedium)
    Text(live.exercise)
    Text("${live.weightGrams/1000.0} kg × ${live.reps} 次 · ${live.done}/${live.total} 组")
    if(status==PresenceStatus.REST) {
        val remaining=live.restEndsAt?.epochSecond?.minus(now.epochSecond)?.coerceAtLeast(0) ?: 0
        Text("休息 %02d:%02d".format(remaining/60,remaining%60),color=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun CommentsDialog(repository:SocialRepository,me:SocialProfile,post:SocialPost,onPerson:(String)->Unit,onClose:()->Unit,onChanged:()->Unit) {
    val scope=rememberCoroutineScope()
    var comments by remember {mutableStateOf<List<SocialComment>>(emptyList())}
    var text by rememberSaveable {mutableStateOf("")}
    var id by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}
    var more by remember {mutableStateOf(true)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun launch(block:suspend ()->Unit) {
        if(busy)return
        scope.launch {
            busy=true
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
            finally{busy=false}
        }
    }
    LaunchedEffect(post.id){launch {comments=repository.comments(post.id);more=comments.size==50}}
    AlertDialog(onDismissRequest=onClose,title={Text("评论")},text={
        Column {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            LazyColumn(Modifier.heightIn(max=320.dp)) {
                items(comments,key={it.id}) {comment->
                    Column {
                        TextButton(onClick={onClose();onPerson(comment.ownerId)}){Text(comment.nickname)}
                        Text(comment.body)
                        Text(comment.createdAt.socialTime(),style=MaterialTheme.typography.labelSmall)
                        if(comment.ownerId==me.id || post.ownerId==me.id) TextButton(onClick={launch {
                            repository.deleteComment(post.id,comment.id);comments=comments.filterNot {it.id==comment.id};onChanged()
                        }},enabled=!busy){Text("删除")}
                    }
                }
                if(more) item {TextButton(onClick={launch {
                    val page=repository.comments(post.id,comments.lastOrNull()?.cursor)
                    comments=(comments+page).distinctBy {it.id}.takeLast(100);more=page.size==50
                }},enabled=!busy){Text("更早评论")}}
            }
            OutlinedTextField(text,{text=it.take(1000)},label={Text("写评论")},maxLines=3)
        }
    },confirmButton={TextButton(onClick={launch {
        repository.comment(post.id,text,id);text="";id=UUID.randomUUID().toString()
        comments=repository.comments(post.id);onChanged()
    }},enabled=!busy && text.isNotBlank()){Text("发送")}},
        dismissButton={TextButton(onClick=onClose){Text("关闭")}})
}
