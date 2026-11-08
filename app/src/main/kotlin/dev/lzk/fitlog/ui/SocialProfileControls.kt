package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel
import kotlinx.coroutines.*

@Composable internal fun AvatarEditor(repository:SocialRepository,profile:SocialProfile) {
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val picker=rememberImagePicker(onImage={bytes->
        busy=true
        try {repository.saveAvatar(repository.uploadImage(bytes))} finally {busy=false}
    },onError={error=it})
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        SocialAvatar(repository,profile){if(!busy)picker()}
        TextButton(onClick=picker,enabled=!busy){Text(if(busy)"上传中…" else "修改头像")}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
}

@Composable internal fun SharingControls(model:FriendsViewModel,repository:SocialRepository,syncHistory:suspend ()->Unit) {
    val scope=rememberCoroutineScope()
    var privacy by remember {mutableStateOf(SharingPreferences())}
    var confirm by remember {mutableStateOf<SharingPreferences?>(null)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    fun launch(block:suspend ()->Unit) {
        if(busy)return
        scope.launch {
            busy=true;message=null
            try {block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message}
            finally{busy=false}
        }
    }
    LaunchedEffect(Unit){launch {privacy=model.repositorySharing()}}
    fun change(value:SharingPreferences) {
        if((value.history || value.live) && repository.boundTrainingOwner()!=model.profile.value?.id) confirm=value
        else launch {model.updateSharing(value);privacy=value}
    }
    HorizontalDivider()
    Text("训练共享",style=MaterialTheme.typography.titleMedium)
    Text("仅好友可见",style=MaterialTheme.typography.bodySmall)
    Row {
        Text("训练历史",androidx.compose.ui.Modifier.weight(1f))
        Switch(privacy.history,{change(privacy.copy(history=it))},enabled=!busy)
    }
    Row {
        Text("实时训练详情",androidx.compose.ui.Modifier.weight(1f))
        Switch(privacy.live,{change(privacy.copy(live=it))},enabled=!busy)
    }
    if(privacy.history) OutlinedButton(onClick={launch {syncHistory();message="训练历史已同步"}},enabled=!busy){
        Text(if(busy)"同步中…" else "同步全部训练历史")
    }
    message?.let {Text(it,color=MaterialTheme.colorScheme.primary)}
    confirm?.let {value->
        AlertDialog(onDismissRequest={confirm=null},title={Text("绑定本机训练记录？")},
            text={Text("本机现有及后续训练将关联当前 FitLog 账号。仅开启的共享项会上传，切换账号后需重新确认。")},
            confirmButton={TextButton(onClick={confirm=null;launch {
                repository.bindTrainingOwner();model.updateSharing(value);privacy=value
            }}){Text("确认绑定并开启")}},
            dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})
    }
}

@Composable internal fun BlockedFriends(repository:SocialRepository,model:FriendsViewModel) {
    val scope=rememberCoroutineScope()
    var show by remember {mutableStateOf(false)}
    var people by remember {mutableStateOf<List<SocialProfile>>(emptyList())}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    TextButton(onClick={show=true;scope.launch {
        try {people=repository.blocked()} catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
    }}) {Text("黑名单")}
    if(show) AlertDialog(onDismissRequest={show=false},title={Text("黑名单")},text={
        Column {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(people.isEmpty()) Text("暂无已拉黑用户")
            LazyColumn {
                items(people,key={it.id}) {person->
                    Row {
                        Text(person.nickname,androidx.compose.ui.Modifier.weight(1f))
                        TextButton(onClick={
                            if(!busy) scope.launch {
                                busy=true
                                try {model.unblock(person.id);people=repository.blocked()}
                                catch(e:CancellationException){throw e}catch(e:Exception){error=e.message}
                                finally{busy=false}
                            }
                        },enabled=!busy){Text("解除拉黑")}
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick={show=false}){Text("关闭")}})
}
