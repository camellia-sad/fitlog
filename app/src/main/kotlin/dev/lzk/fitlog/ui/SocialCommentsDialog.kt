package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID

private const val COMMENT_PAGE=50

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CommentsDialog(repository:SocialRepository,me:SocialProfile,post:SocialPost,onPerson:(String)->Unit,
    onClose:()->Unit,onChanged:(Int)->Unit,focusContext:CommentContext?=null,onViewed:(String)->Unit={}) {
    val scope=rememberCoroutineScope()
    val state=rememberLazyListState()
    var comments by remember(post.id){mutableStateOf<List<SocialComment>>(emptyList())}
    var replies by remember(post.id){mutableStateOf<Map<String,List<SocialComment>>>(emptyMap())}
    var cursors by remember(post.id){mutableStateOf<Map<String,SocialCursor?>>(emptyMap())}
    var moreReplies by remember(post.id){mutableStateOf<Set<String>>(emptySet())}
    var expanded by remember(post.id){mutableStateOf<Set<String>>(emptySet())}
    var target by remember {mutableStateOf<SocialComment?>(null)}
    var deleting by remember {mutableStateOf<SocialComment?>(null)}
    var highlighted by remember {mutableStateOf<String?>(null)}
    var text by rememberSaveable(post.id){mutableStateOf("")}
    var id by rememberSaveable(post.id){mutableStateOf(UUID.randomUUID().toString())}
    var more by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val focus=remember {androidx.compose.ui.focus.FocusRequester()}
    fun launch(block:suspend ()->Unit) {
        if(busy)return
        busy=true
        scope.launch {
            error=null
            try{block()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "操作失败，请重试"}
            finally{busy=false}
        }
    }
    suspend fun loadReplies(root:String) {
        val page=repository.replies(post.id,root)
        replies=replies+(root to page);cursors=cursors+(root to page.lastOrNull()?.cursor)
        moreReplies=if(page.size==COMMENT_PAGE)moreReplies+root else moreReplies-root
    }
    fun delete(comment:SocialComment)=launch {
        repository.deleteComment(post.id,comment.id)
        val root=comment.parentId
        if(root==null) {
            comments=if(comment.replyCount>0)comments.map{if(it.id==comment.id)it.copy(deleted=true,body="") else it}
                else comments.filterNot{it.id==comment.id}
        } else {
            replies=replies+(root to replies[root].orEmpty().filterNot{it.id==comment.id})
            comments=comments.mapNotNull{
                if(it.id!=root)it else it.copy(replyCount=(it.replyCount-1).coerceAtLeast(0)).takeUnless{c->c.deleted && c.replyCount==0}
            }
        }
        deleting=null;if(target?.id==comment.id)target=null;onChanged(-1)
    }
    LaunchedEffect(post.id,focusContext?.target?.id) {
        launch {
            comments=repository.comments(post.id);more=comments.size==COMMENT_PAGE
            focusContext?.let {ctx->
                comments=(comments+ctx.root).distinctBy{it.id}
                ctx.target.parentId?.let {root->
                    loadReplies(root)
                    replies=replies+(root to (replies[root].orEmpty()+ctx.target).distinctBy{it.id}.sortedWith(compareBy({it.createdAt},{it.id})))
                    expanded=expanded+root
                }
                highlighted=ctx.target.id
            }
        }
    }
    // Every visible row has a stable key; locating an old reply never requires loading the whole thread.
    val rowKeys=buildList {
        comments.forEach {c->
            add(c.id)
            if(c.replyCount>0)add("toggle:"+c.id)
            if(c.id in expanded) {
                replies[c.id].orEmpty().forEach{add(it.id)}
                if(c.id in moreReplies)add("more:"+c.id)
            }
        }
        if(more)add("older")
    }
    LaunchedEffect(highlighted,rowKeys) {
        val key=highlighted ?: return@LaunchedEffect
        val index=rowKeys.indexOf(key)
        if(index>=0) {
            state.scrollToItem(index)
            snapshotFlow{state.layoutInfo.visibleItemsInfo.any{it.key==key}}.first{it}
            if(key==focusContext?.target?.id)onViewed(key)
            delay(2600);highlighted=null
        }
    }
    ModalBottomSheet(onDismissRequest=onClose,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding().padding(horizontal=16.dp)) {
            Row(Modifier.padding(bottom=6.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("评论",style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Black))
                Text(post.commentCount.toString(),Modifier.weight(1f).padding(bottom=2.dp),style=pixelNumber(21.sp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                SquareKey(Symbol.CLOSE,"关闭评论",size=34.dp,onClick=onClose)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f).testTag("activity-comments"),state=state,contentPadding=PaddingValues(vertical=12.dp)) {
                if(comments.isEmpty() && !busy)item {Text("还没有评论",Modifier.padding(8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                comments.forEach {comment->
                    item(key=comment.id) {
                        CommentRow(repository,comment,me,busy,highlighted==comment.id,author=comment.ownerId==post.ownerId,onPerson={onClose();onPerson(comment.ownerId)},
                            onReply={target=comment;focus.requestFocus()},onDelete={deleting=comment})
                    }
                    if(comment.replyCount>0)item(key="toggle:"+comment.id) {
                        ThreadToggle(if(comment.id in expanded)"收起回复" else "展开 ${comment.replyCount} 条回复",!busy) {
                            if(comment.id in expanded)expanded=expanded-comment.id
                            else launch{loadReplies(comment.id);expanded=expanded+comment.id}
                        }
                    }
                    if(comment.id in expanded) {
                        replies[comment.id].orEmpty().forEach {reply->
                            item(key=reply.id) {
                                Row(Modifier.padding(start=42.dp).height(IntrinsicSize.Min)) {
                                    Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer))
                                    CommentRow(repository,reply,me,busy,highlighted==reply.id,
                                        Modifier.weight(1f).padding(start=8.dp),author=reply.ownerId==post.ownerId,
                                        onPerson={onClose();onPerson(reply.ownerId)},onReply={target=reply;focus.requestFocus()},onDelete={deleting=reply})
                                }
                            }
                        }
                        if(comment.id in moreReplies)item(key="more:"+comment.id) {
                            ThreadToggle("更多回复",!busy) {launch {
                                val page=repository.replies(post.id,comment.id,cursors[comment.id])
                                replies=replies+(comment.id to (replies[comment.id].orEmpty()+page).distinctBy{it.id}.sortedWith(compareBy({it.createdAt},{it.id})).takeLast(150))
                                cursors=cursors+(comment.id to page.lastOrNull()?.cursor)
                                moreReplies=if(page.size==COMMENT_PAGE)moreReplies else moreReplies-comment.id
                            }}
                        }
                    }
                }
                if(more)item(key="older"){ThreadToggle("更早评论",!busy,start=0.dp){launch {
                    val page=repository.comments(post.id,comments.filter{it.id!=focusContext?.root?.id}.lastOrNull()?.cursor ?: comments.lastOrNull()?.cursor)
                    comments=(comments+page).distinctBy{it.id}.takeLast(100);more=page.size==COMMENT_PAGE
                }}}
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            target?.let{reply->
                Row(Modifier.fillMaxWidth().padding(top=8.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(12.dp))
                    .padding(start=12.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    LineIcon(Symbol.REPLY,Modifier.size(12.dp),MaterialTheme.colorScheme.primary)
                    OneLineText("正在回复 @"+reply.nickname,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    SquareKey(Symbol.CLOSE,"取消回复",size=28.dp){target=null}
                }
            }
            Row(Modifier.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text,{text=it.take(1000)},placeholder={Text(if(target==null)"写评论" else "回复 @"+target!!.nickname)},
                    maxLines=4,shape=RoundedCornerShape(22.dp),modifier=Modifier.weight(1f).focusRequester(focus))
                PixelKey(onClick={launch {
                    val reply=target
                    val sentId=id
                    val sentText=text.trim()
                    repository.comment(post.id,text,id,reply?.id)
                    text="";id=UUID.randomUUID().toString();target=null
                    val old=comments
                    comments=repository.comments(post.id);more=comments.size==COMMENT_PAGE
                    val root=reply?.let{it.parentId ?: it.id}
                    if(root!=null) {
                        if(comments.none{it.id==root})old.find{it.id==root}?.let{comments=comments+it.copy(replyCount=it.replyCount+1)}
                        loadReplies(root);expanded=expanded+root
                        val sent=SocialComment(sentId,me.id,me.nickname,sentText,java.time.Instant.now(),parentId=root,
                            replyToNickname=if(reply?.parentId!=null)reply.nickname else null,avatarPath=me.avatarPath,fitlogId=me.fitlogId)
                        replies=replies+(root to (replies[root].orEmpty()+sent).distinctBy{it.id}.sortedWith(compareBy({it.createdAt},{it.id})).takeLast(150))
                        highlighted=sentId
                    }
                    onChanged(1)
                }},enabled=!busy && text.isNotBlank(),minHeight=44.dp,minWidth=44.dp,contentPadding=PaddingValues(0.dp),
                    modifier=Modifier.semanticsLabel("发送").testTag("comment-send")){LineIcon(Symbol.SEND,Modifier.size(15.dp),LocalContentColor.current)}
            }
        }
    }
    deleting?.let{c->AlertDialog(onDismissRequest={deleting=null},title={Text("删除这条评论？")},
        text={Text(if(c.parentId==null && c.replyCount>0)"回复会保留，这条评论将显示为已删除。" else "删除后无法恢复。")},
        confirmButton={TextButton(onClick={delete(c)},enabled=!busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("删除")}},
        dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

@Composable private fun CommentRow(repository:SocialRepository,comment:SocialComment,me:SocialProfile,busy:Boolean,highlighted:Boolean,
    modifier:Modifier=Modifier,author:Boolean=false,onPerson:()->Unit,onReply:()->Unit,onDelete:()->Unit) {
    val color=MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().background(if(highlighted)color.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        RoundedCornerShape(14.dp)).padding(vertical=10.dp,horizontal=6.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        SocialAvatar(repository,SocialProfile(comment.ownerId,comment.fitlogId,comment.nickname,avatarPath=comment.avatarPath),
            size=if(comment.parentId==null)36.dp else 30.dp,onClick=onPerson)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(comment.nickname,Modifier.weight(1f,fill=false).clickable(onClick=onPerson),style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.ExtraBold),
                    color=color.onSurface,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if(author)Text("作者",Modifier.background(color.primary,PixelShapeSmall).padding(horizontal=5.dp),
                    style=MaterialTheme.typography.labelSmall.copy(fontWeight=FontWeight.ExtraBold),color=color.onPrimary,maxLines=1)
                Spacer(Modifier.weight(1f))
                Text(ChatTimes.label(comment.createdAt),style=MaterialTheme.typography.labelSmall,color=color.onSurfaceVariant.copy(alpha=.75f),maxLines=1)
            }
            if(comment.deleted)Text("该评论已删除",style=MaterialTheme.typography.bodyMedium,color=color.onSurfaceVariant)
            else Text(buildAnnotatedString {
                comment.replyToNickname?.let{withStyle(SpanStyle(color=color.primary,fontWeight=FontWeight.Bold)){append("@$it ")}}
                append(comment.body)
            },style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.5.sp,lineHeight=22.sp))
            if(!comment.deleted)Row(Modifier.padding(top=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                CommentChip(Symbol.REPLY,"回复",color.surfaceVariant,color.onSurfaceVariant,!busy,onReply)
                if(comment.ownerId==me.id)CommentChip(Symbol.TRASH,"删除",color.errorContainer.copy(alpha=.6f),color.error,!busy,onDelete)
            }
        }
    }
}

@Composable private fun CommentChip(symbol:Symbol,label:String,fill:androidx.compose.ui.graphics.Color,ink:androidx.compose.ui.graphics.Color,enabled:Boolean,onClick:()->Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(fill).clickable(enabled=enabled,onClick=onClick).heightIn(min=28.dp).padding(horizontal=10.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        LineIcon(symbol,Modifier.size(11.dp),ink)
        Text(label,style=MaterialTheme.typography.labelMedium.copy(fontWeight=FontWeight.Bold),color=ink)
    }
}

/** “展开回复” and friends: a short teal lead line, then the label. */
@Composable private fun ThreadToggle(label:String,enabled:Boolean,start:androidx.compose.ui.unit.Dp=52.dp,onClick:()->Unit) {
    Row(Modifier.padding(start=start).clip(RoundedCornerShape(15.dp)).clickable(enabled=enabled,onClick=onClick).heightIn(min=32.dp).padding(horizontal=8.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(18.dp).height(2.dp).background(MaterialTheme.colorScheme.primary.copy(alpha=.5f)))
        Text(label,style=MaterialTheme.typography.labelMedium.copy(fontWeight=FontWeight.ExtraBold),color=MaterialTheme.colorScheme.primary)
    }
}
