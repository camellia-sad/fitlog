package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.FriendsViewModel

@Composable
fun MeScreen(model:FriendsViewModel,social:SocialRepository?=null,syncHistory:suspend ()->Unit={},
    onSettings:()->Unit={},onSubpage:(Boolean)->Unit={}) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var editing by rememberSaveable{mutableStateOf(false)}
    var personId by rememberSaveable{mutableStateOf<String?>(null)}
    var logout by remember{mutableStateOf(false)}
    LaunchedEffect(editing,personId){onSubpage(editing || personId!=null)}
    DisposableEffect(Unit){onDispose{onSubpage(false)}}
    LaunchedEffect(profile?.id){if(profile==null){editing=false;personId=null}}
    if(social!=null && profile!=null && personId!=null) {
        SocialTimelineScreen(social,requireNotNull(profile),owner=personId,onBack={personId=null},onPerson={personId=it})
        return
    }
    if(editing)BackHandler{editing=false}
    LazyColumn(Modifier.fillMaxSize().testTag("me-list"),contentPadding=PaddingValues(20.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            if(editing) SocialHeader("个人资料",{editing=false})
            else OneLineText("我的",style=MaterialTheme.typography.headlineMedium)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
        }
        val current=profile
        if(editing && current!=null) {
            item {
                var name by rememberSaveable(current.id){mutableStateOf(current.nickname)}
                var bio by rememberSaveable(current.id){mutableStateOf(current.bio)}
                SoftCard {
                    if(social!=null)AvatarEditor(social,current)
                    ProfileIdentity(current)
                    OutlinedTextField(name,{name=it.take(40)},label={Text("昵称")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(bio,{bio=it.take(300)},label={Text("简介")},modifier=Modifier.fillMaxWidth())
                    Button(onClick={model.saveProfile(name,bio)},enabled=!busy){Text("保存资料")}
                    if(social!=null) {
                        TextButton(onClick={personId=current.id}){Text("我的动态与共享历史")}
                        SharingControls(model,social,syncHistory)
                        BlockedFriends(social,model)
                    }
                    OutlinedButton(onClick={logout=true},enabled=!busy){Text("退出登录")}
                }
            }
        } else {
            item {
                SoftCard(Modifier.then(if(current!=null)Modifier.clickable{editing=true} else Modifier)) {
                    if(current==null) {
                        PixelPuppy(Modifier.size(64.dp))
                        OneLineText("记录每一次进步",style=MaterialTheme.typography.titleLarge)
                        Text("未登录也可使用全部离线训练功能。",style=MaterialTheme.typography.bodySmall)
                    }else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        SocialAvatar(social,current){editing=true}
                        Column(Modifier.weight(1f)) {
                            OneLineText(current.nickname,style=MaterialTheme.typography.titleLarge)
                            Text("我的资料与 FitLog ID",style=MaterialTheme.typography.bodySmall)
                        }
                        LineIcon(Symbol.ARROW,Modifier.size(20.dp))
                    }
                }
            }
            item {
                OutlinedButton(onClick=onSettings,modifier=Modifier.fillMaxWidth().testTag("open-settings").heightIn(min=56.dp)) {
                    LineIcon(Symbol.SETTINGS,Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("设置",Modifier.weight(1f))
                    LineIcon(Symbol.ARROW,Modifier.size(20.dp))
                }
            }
            if(current==null)item {
                if(model.configured)LoginCard(model)
                else Text("账号服务尚未配置，离线训练仍可照常使用。")
            }
        }
    }
    if(logout)AlertDialog(onDismissRequest={logout=false},title={Text("退出登录？")},
        text={Text("本机训练、聊天记录和待发送消息会保留，按账号隔离。")},
        confirmButton={TextButton(onClick={logout=false;editing=false;model.signOut()},enabled=!busy){Text("确认")}},
        dismissButton={TextButton(onClick={logout=false}){Text("取消")}})
}

@Composable private fun LoginCard(model:FriendsViewModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val codeSent by model.codeSent.collectAsStateWithLifecycle()
    var email by rememberSaveable{mutableStateOf("")}
    var code by rememberSaveable{mutableStateOf("")}
    SoftCard {
        OneLineText("登录，和好友一起进步",style=MaterialTheme.typography.titleLarge)
        Text("登录仅用于社交，不会自动上传本机训练历史。",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(email,{email=it.trim();code=""},label={Text("邮箱")},singleLine=true,
            enabled=!busy && !codeSent,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
        if(!codeSent)TextButton(onClick={model.codeSent.value=true},enabled=!busy && email.isNotBlank()){Text("已有验证码")}
        if(codeSent) {
            OutlinedTextField(code,{code=it},label={Text("邮件验证码")},singleLine=true,
                enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            Button(onClick={model.verify(email,code)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("验证并登录")}
            TextButton(onClick={model.codeSent.value=false;code=""},enabled=!busy){Text("更换邮箱")}
        }
        OutlinedButton(onClick={model.requestCode(email)},enabled=!busy && email.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
            Text(if(codeSent)"重新发送验证码" else "发送验证码")
        }
    }
}
