package dev.lzk.fitlog.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*

internal val ActivityLavender=Color(0xFFBCA4FF)
internal val ActivityCoral=Color(0xFFFF978F)
internal val ActivityAmber=Color(0xFFFFCF82)

@Composable internal fun ActivityCard(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable internal fun ActivityGlyph(kind:String,liked:Boolean=false,modifier:Modifier=Modifier) {
    val tint=MaterialTheme.colorScheme.onSurfaceVariant
    val mint=MaterialTheme.colorScheme.primary
    Canvas(modifier.size(25.dp)) {
        val u=size.width/32f
        fun box(x:Float,y:Float,w:Float,h:Float,color:Color,outline:Boolean=false) {
            drawRect(color,Offset(x*u,y*u),Size(w*u,h*u),style=if(outline) Stroke(1.6f*u) else androidx.compose.ui.graphics.drawscope.Fill)
        }
        if(kind=="like") {
            if(liked) {
                box(3f,8f,5f,16f,ActivityLavender);box(8f,12f,4f,8f,Color(0xFFA27FEA))
                box(12f,14f,8f,4f,mint);box(20f,12f,4f,8f,Color(0xFFA27FEA));box(24f,8f,5f,16f,ActivityLavender)
                box(1f,12f,2f,8f,ActivityAmber);box(29f,12f,2f,8f,ActivityAmber)
                box(3f,4f,2f,2f,ActivityAmber);box(25f,26f,2f,2f,mint)
            } else {
                box(3f,10f,5f,12f,tint,true);box(8f,13f,4f,6f,tint,true)
                box(12f,15f,8f,2f,tint,true);box(20f,13f,4f,6f,tint,true);box(24f,10f,5f,12f,tint,true)
            }
        } else {
            drawRoundRect(tint,Offset(4*u,5*u),Size(24*u,20*u),androidx.compose.ui.geometry.CornerRadius(7*u),style=Stroke(1.8f*u))
            drawLine(tint,Offset(8*u,24*u),Offset(6*u,29*u),1.8f*u)
            drawLine(tint,Offset(6*u,29*u),Offset(14*u,25*u),1.8f*u)
        }
    }
}

@Composable internal fun ReplyNoticeRow(repository:SocialRepository,notice:ReplyNotice,onClick:()->Unit) {
    val c=notice.comment
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        SocialAvatar(repository,SocialProfile(c.ownerId,c.fitlogId,c.nickname,avatarPath=c.avatarPath),onClick)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(c.nickname,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                Text(ChatTimes.label(c.createdAt),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.75f))
            }
            Text(c.body,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
            Text("来自动态 · "+notice.postPreview.ifBlank{"图片动态"},maxLines=1,overflow=TextOverflow.Ellipsis,
                color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.padding(top=6.dp).size(8.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** New replies: the latest repliers' avatars stacked, a count, and a key that unfolds up to three or opens them all. */
@Composable internal fun ReplyInboxBar(repository:SocialRepository,count:Int,notices:List<ReplyNotice>,onOpen:()->Unit,onSelect:(ReplyNotice)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    LaunchedEffect(count){if(count==0 || count>3)expanded=false}
    if(count<=0)return
    val scheme=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(16.dp),color=scheme.primaryContainer,modifier=Modifier.fillMaxWidth().testTag("reply-inbox")) {
        Column {
            Row(Modifier.fillMaxWidth().clickable {if(count>3)onOpen() else expanded=!expanded}.testTag("reply-inbox-head").padding(horizontal=12.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width((28+18*(notices.take(3).size-1).coerceAtLeast(0)).dp).height(28.dp)) {
                    notices.map{it.comment}.distinctBy{it.ownerId}.take(3).forEachIndexed {i,c->
                        Box(Modifier.offset(x=(18*i).dp).size(28.dp).background(scheme.primaryContainer,CircleShape).padding(2.dp)) {
                            SocialAvatar(repository,SocialProfile(c.ownerId,c.fitlogId,c.nickname,avatarPath=c.avatarPath),size=24.dp) {}
                        }
                    }
                }
                Text("新回复",style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.Bold),color=scheme.onSurface)
                Text("$count",Modifier.weight(1f),style=pixelNumber(19.sp),color=scheme.primary)
                LineIcon(if(count>3)Symbol.ARROW else if(expanded)Symbol.UP else Symbol.DOWN,Modifier.size(12.dp).semanticsLabel(
                    if(count>3)"查看全部回复" else if(expanded)"收起回复" else "展开回复"),scheme.primary)
            }
            if(expanded && count<=3)notices.forEach {n->key(n.comment.id){ReplyNoticeRow(repository,n){onSelect(n)}}}
        }
    }
}

@Composable internal fun ReplyMessagesScreen(repository:SocialRepository,count:Int,notices:List<ReplyNotice>,busy:Boolean,error:String?,
    onBack:()->Unit,onReadAll:()->Unit,onMore:()->Unit,more:Boolean,onSelect:(ReplyNotice)->Unit) {
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background,contentColor=MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        SocialHeader("回复消息",onBack){TextButton(onClick=onReadAll,enabled=!busy && count>0){Text("全部已读")}}
        Text("$count 条未读",color=ActivityAmber,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=12.dp))
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=20.dp)) {
            if(notices.isEmpty())item {Text("所有回复都已查看")}
            items(notices,key={it.comment.id}){n->ActivityCard {ReplyNoticeRow(repository,n){onSelect(n)}}}
            if(more)item {TextButton(onClick=onMore,enabled=!busy){Text("更早回复")}}
        }
    }
}
}
