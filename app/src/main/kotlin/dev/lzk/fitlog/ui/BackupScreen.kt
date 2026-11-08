package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.AppContainer
import dev.lzk.fitlog.data.PortableBackupRepository
import dev.lzk.fitlog.data.SupabaseSocialRepository
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BackupScreen(container:AppContainer,onBack:()->Unit,onLogin:()->Unit) {
    val context=LocalContext.current
    val profile by container.social.profile.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val owner=profile?.id
    var includeChat by remember(owner) {mutableStateOf(owner!=null)}
    var busy by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var backups by remember(owner) {mutableStateOf<List<SupabaseSocialRepository.CloudBackup>>(emptyList())}
    var preview by remember(owner) {mutableStateOf<PortableBackupRepository.Preview?>(null)}
    var deleting by remember {mutableStateOf<SupabaseSocialRepository.CloudBackup?>(null)}
    var active by remember {mutableStateOf<Job?>(null)}
    fun task(block:suspend ()->Unit) {
        if(busy) return
        active=scope.launch {
            busy=true;message=null
            try {block()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {message=e.message ?: "备份操作失败，请重试"}
            finally {busy=false}
        }
    }
    LaunchedEffect(owner) {active?.cancelAndJoin();busy=false;preview=null;deleting=null;message=null}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->
        if(uri!=null) task {
            val bytes=container.portableBackups.exportLocalJson().toByteArray()
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use {it.write(bytes)}
            }
            message="完整备份已导出"
        }
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) task {
            val bytes=withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use {input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {
                        val n=input.read(buffer);if(n<0) break
                        require(output.size()+n<=PortableBackupRepository.MAX_ARCHIVE) {"完整备份最大 50 MB"}
                        output.write(buffer,0,n)
                    }
                    output.toByteArray()
                }
            }
            container.portableBackups.importLocalJson(bytes.toString(Charsets.UTF_8));message="本地 JSON 已合并，图片请从云端备份恢复"
        }
    }
    BackHandler(enabled=!busy,onBack=onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        SocialHeader("备份与恢复",if(busy) ({}) else onBack)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("云端备份包含聊天",Modifier.weight(1f).padding(top=12.dp))
            Switch(includeChat,{includeChat=it},enabled=!busy && owner!=null)
        }
        Text("本地 JSON 不含图片",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={exporter.launch("FitLog-"+java.time.LocalDate.now()+".json")},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("导出本地 JSON")}
        OutlinedButton(onClick={importer.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("导入本地 JSON")}
        HorizontalDivider()
        Text("云端备份",style=MaterialTheme.typography.titleLarge)
        if(owner==null) {
            Text("登录后可保存和恢复自己的私有备份。")
            Button(onClick=onLogin) {Text("前往登录")}
        } else {
            OneLineText("备份账号："+profile!!.nickname,style=MaterialTheme.typography.titleSmall)
            Text("仅本人可访问，保留最近三份",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={task {
                container.social.prepareOwnMedia(owner)
                val bytes=container.portableBackups.export(includeChat)
                container.social.uploadBackup(bytes,owner)
                check(container.social.profile.value?.id==owner) {"账号已切换"}
                backups=container.social.cloudBackups(owner)
                message="云端备份已保存"
            }},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("备份到云端")}
            OutlinedButton(onClick={task {backups=container.social.cloudBackups(owner);if(backups.isEmpty()) message="尚无云端备份"}},
                enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("从云端恢复")}
            backups.forEach {backup->
                SoftCard {
                    Text(backup.createdAt?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: "备份",
                        style=MaterialTheme.typography.titleSmall)
                    Text("%.1f MB".format(backup.size/1024.0/1024),style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick={task {
                            val bytes=container.social.downloadBackup(backup.name,owner)
                            check(container.social.profile.value?.id==owner) {"账号已切换"}
                            preview=container.portableBackups.preview(bytes)
                        }},enabled=!busy) {Text("查看并恢复")}
                        TextButton(onClick={deleting=backup},enabled=!busy) {Text("删除备份")}
                    }
                }
            }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let {Text(it,color=MaterialTheme.colorScheme.primary)}
    }
    preview?.let {item->
        AlertDialog(onDismissRequest={if(!busy) preview=null},title={Text("恢复这份备份？")},text={
            Text("备份时间："+item.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))+
                if(item.includesChat) "\n包含本机训练与设置、"+item.messageCount+" 条聊天、"+item.mediaCount+" 张图片。\n现有删除和撤回状态优先，聊天仅恢复到本机。"
                else "\n包含训练、模板、计划、偏好、草稿及本人动态图片 "+item.mediaCount+" 张，不含聊天。")
        },confirmButton={TextButton(enabled=!busy,onClick={task {
            if(owner!=null) check(container.social.profile.value?.id==owner) {"账号已切换"}
            container.portableBackups.restore(item);preview=null;message="备份恢复完成"
        }}) {Text("确认恢复")}},dismissButton={TextButton(enabled=!busy,onClick={preview=null}) {Text("取消")}})
    }
    deleting?.let {item->
        AlertDialog(onDismissRequest={deleting=null},title={Text("删除这份云端备份？")},text={Text("本机记录保留，此备份删除后无法恢复。")},
            confirmButton={TextButton(enabled=!busy,onClick={deleting=null;task {
                val account=owner ?: error("请先登录")
                container.social.deleteBackups(listOf(item.name),account)
                backups=container.social.cloudBackups(account);message="云端备份已删除"
            }}) {Text("删除")}},dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}})
    }
}
