package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

@Composable internal fun WorkoutShareDialog(workout:Workout,friends:FriendsRepository,social:SocialRepository,
    onClose:()->Unit,onLogin:()->Unit,onPublished:()->Unit) {
    val profile by friends.profile.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var body by rememberSaveable(workout.session.id){mutableStateOf(workout.socialPostText())}
    var id by rememberSaveable(workout.session.id){mutableStateOf(UUID.randomUUID().toString())}
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
    AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text("分享到动态")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("好友可见；不会开启训练历史或实时共享。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(body,{body=it.take(4000)},label={Text("训练动态内容")},modifier=Modifier.fillMaxWidth().testTag("workout-post-body"),maxLines=9,enabled=!busy)
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(onClick={
        if(!busy)scope.launch {
            busy=true;error=null
            try {
                check(friends.profile.value?.id==account) {"账号已切换"}
                social.publish(PostDraft(id,body))
                onPublished()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message ?: "发表失败，请重试"}
            finally{busy=false}
        }
    },enabled=!busy && body.isNotBlank()){Text(if(busy)"发表中…" else "发表")}},
        dismissButton={TextButton(onClick=onClose,enabled=!busy){Text("取消")}})
}
