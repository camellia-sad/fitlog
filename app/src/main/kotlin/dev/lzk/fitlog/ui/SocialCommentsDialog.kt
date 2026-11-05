package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

private const val COMMENT_PAGE=50

/** Top-level comments with replies expanded beneath them; a reply to a reply stays in the same thread. */
@Composable internal fun CommentsDialog(repository:SocialRepository,me:SocialProfile,post:SocialPost,onPerson:(String)->Unit,onClose:()->Unit,onChanged:()->Unit) {
    val scope=rememberCoroutineScope()
    var comments by remember {mutableStateOf<List<SocialComment>>(emptyList())}
    var replies by remember {mutableStateOf<Map<String,List<SocialComment>>>(emptyMap())}
    var moreReplies by remember {mutableStateOf<Set<String>>(emptySet())}
    var expanded by remember {mutableStateOf<Set<String>>(emptySet())}
    var target by remember {mutableStateOf<SocialComment?>(null)}
    var text by rememberSaveable {mutableStateOf("")}
    var id by rememberSaveable {mutableStateOf(UUID.randomUUID().toString())}
    var more by remember {mutableStateOf(true)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun launch(block:suspend ()->Unit) {
        if(busy)return
        scope.launch {
            busy=true;error=null
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
            finally{busy=false}
        }
    }
    suspend fun loadReplies(root:String) {
        val page=repository.replies(post.id,root)
        replies=replies+(root to page)
        moreReplies=if(page.size==COMMENT_PAGE) moreReplies+root else moreReplies-root
    }
    fun delete(comment:SocialComment) = launch {
        repository.deleteComment(post.id,comment.id)
        val root=comment.parentId
        if(root==null) {
            comments=if(comment.replyCount>0) comments.map {if(it.id==comment.id) it.copy(deleted=true,body="") else it}
                else comments.filterNot {it.id==comment.id}
        } else {
            replies=replies+(root to replies[root].orEmpty().filterNot {it.id==comment.id})
            comments=comments.mapNotNull {
                if(it.id!=root) it else it.copy(replyCount=(it.replyCount-1).coerceAtLeast(0)).takeUnless {c->c.deleted && c.replyCount==0}
            }
        }
        if(target?.id==comment.id) target=null
        onChanged()
    }
    LaunchedEffect(post.id){launch {comments=repository.comments(post.id);more=comments.size==COMMENT_PAGE}}
    AlertDialog(onDismissRequest=onClose,title={Text("评论")},text={
        Column {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            LazyColumn(Modifier.heightIn(max=360.dp)) {
                comments.forEach {comment->
                    item(key=comment.id) {
                        CommentRow(comment,me,busy,onPerson={onClose();onPerson(comment.ownerId)},onReply={target=comment},onDelete={delete(comment)})
                    }
                    if(comment.replyCount>0) item(key="toggle:"+comment.id) {
                        val open=comment.id in expanded
                        TextButton(onClick={
                            if(open) expanded=expanded-comment.id
                            else launch {loadReplies(comment.id);expanded=expanded+comment.id}
                        },enabled=!busy,modifier=Modifier.padding(start=24.dp)) {
                            Text(if(open) "收起回复" else "展开 ${comment.replyCount} 条回复")
                        }
                    }
                    if(comment.id in expanded) {
                        items(replies[comment.id].orEmpty(),key={"reply:"+it.id}) {reply->
                            CommentRow(reply,me,busy,Modifier.padding(start=24.dp),onPerson={onClose();onPerson(reply.ownerId)},
                                onReply={target=reply},onDelete={delete(reply)})
                        }
                        if(comment.id in moreReplies) item(key="more:"+comment.id) {
                            TextButton(onClick={launch {
                                val loaded=replies[comment.id].orEmpty()
                                val page=repository.replies(post.id,comment.id,loaded.lastOrNull()?.cursor)
                                replies=replies+(comment.id to (loaded+page).distinctBy {it.id})
                                moreReplies=if(page.size==COMMENT_PAGE) moreReplies else moreReplies-comment.id
                            }},enabled=!busy,modifier=Modifier.padding(start=24.dp)){Text("更多回复")}
                        }
                    }
                }
                if(more) item {TextButton(onClick={launch {
                    val page=repository.comments(post.id,comments.lastOrNull()?.cursor)
                    comments=(comments+page).distinctBy {it.id}.takeLast(100);more=page.size==COMMENT_PAGE
                }},enabled=!busy){Text("更早评论")}}
            }
            target?.let {reply->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    OneLineText("回复 @"+reply.nickname,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                    TextButton(onClick={target=null}){Text("取消")}
                }
            }
            OutlinedTextField(text,{text=it.take(1000)},label={Text(if(target==null) "写评论" else "写回复")},maxLines=3)
        }
    },confirmButton={TextButton(onClick={launch {
        val reply=target
        repository.comment(post.id,text,id,reply?.id);text="";id=UUID.randomUUID().toString();target=null
        comments=repository.comments(post.id);more=comments.size==COMMENT_PAGE
        val root=reply?.let {it.parentId ?: it.id}
        if(root!=null) {loadReplies(root);expanded=expanded+root}
        expanded.filter {it!=root}.forEach {loadReplies(it)}
        onChanged()
    }},enabled=!busy && text.isNotBlank()){Text("发送")}},
        dismissButton={TextButton(onClick=onClose){Text("关闭")}})
}

@Composable private fun CommentRow(comment:SocialComment,me:SocialProfile,busy:Boolean,modifier:Modifier=Modifier,
    onPerson:()->Unit,onReply:()->Unit,onDelete:()->Unit) {
    Column(modifier) {
        if(comment.deleted) {
            Text("该评论已删除",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium,
                modifier=Modifier.padding(vertical=8.dp))
            return@Column
        }
        TextButton(onClick=onPerson){Text(comment.nickname)}
        val replyTo=comment.replyToNickname
        if(replyTo==null) Text(comment.body)
        else Text(buildAnnotatedString {
            withStyle(SpanStyle(color=MaterialTheme.colorScheme.primary)){append("回复 @$replyTo：")}
            append(comment.body)
        })
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(comment.createdAt.socialTime(),style=MaterialTheme.typography.labelSmall,modifier=Modifier.weight(1f))
            TextButton(onClick=onReply,enabled=!busy){Text("回复")}
            if(comment.ownerId==me.id) TextButton(onClick=onDelete,enabled=!busy){Text("删除")}
        }
    }
}
