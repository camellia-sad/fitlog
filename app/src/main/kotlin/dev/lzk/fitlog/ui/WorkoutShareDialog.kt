package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Sharing a finished workout: an optional line to say, then a preview of the training card friends will see. The
 * post also carries the plain-text version for older apps. Publishing does not turn on any training sharing.
 */
@Composable internal fun WorkoutShareDialog(workout:Workout,friends:FriendsRepository,social:SocialRepository,
    onClose:()->Unit,onLogin:()->Unit,onPublished:()->Unit,unit:WeightUnit=WeightUnit.KG,catalog:List<Exercise> = emptyList(),
    loadRecords:suspend (Workout)->Map<String,Long> = {emptyMap()}) {
    val profile by friends.profile.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var caption by rememberSaveable(workout.session.id){mutableStateOf("")}
    var id by rememberSaveable(workout.session.id){mutableStateOf(UUID.randomUUID().toString())}
    var records by remember(workout.session.id){mutableStateOf<Map<String,Long>>(emptyMap())}
    LaunchedEffect(workout.session.id) {records=try {loadRecords(workout)} catch(e:CancellationException) {throw e} catch(_:Exception) {emptyMap()}}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    if(profile==null) {
        AlertDialog(onDismissRequest=onClose,title={Text("登录后分享到动态")},
            text={Text("训练和感想保留在本机，登录后可从历史页继续分享。")},
            confirmButton={TextButton(onClick=onLogin){Text("去登录")}},
            dismissButton={TextButton(onClick=onClose){Text("取消")}})
        return
    }
    val account=requireNotNull(profile).id
    val card=remember(workout,records,unit,caption,catalog) {workout.postWorkout(catalog,records,unit,caption)}
    AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text("分享到动态")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("好友可见；不会开启训练历史或实时共享。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(caption,{caption=it.take(1000)},placeholder={Text("说点什么")},modifier=Modifier.fillMaxWidth().testTag("workout-post-body"),
                maxLines=4,enabled=!busy,shape=RoundedCornerShape(14.dp))
            WorkoutCard(card)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={PixelKey(onClick={
        if(!busy)scope.launch {
            busy=true;error=null
            try {
                check(friends.profile.value?.id==account) {"账号已切换"}
                // Older apps show the text; this one shows the caption above the card.
                val text=(caption.trim().takeIf{it.isNotBlank()}?.let{it+"\n\n"}.orEmpty())+workout.socialPostText(unit=unit)
                social.publish(PostDraft(id,text.take(4000),workout=card))
                onPublished()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message ?: "发表失败，请重试"}
            finally{busy=false}
        }
    },enabled=!busy,minHeight=38.dp,modifier=Modifier.testTag("publish-workout")){Text(if(busy)"发表中…" else "发表")}},
        dismissButton={TextButton(onClick=onClose,enabled=!busy){Text("取消")}})
}
