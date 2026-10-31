package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lzk.fitlog.BuildConfig
import dev.lzk.fitlog.updates.*
import kotlinx.coroutines.*
import java.io.File

@Composable
fun UpdateEntry(updates:AppUpdates) {
    val scope=rememberCoroutineScope()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var release by remember {mutableStateOf<AppRelease?>(null)}
    var dialog by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var progress by remember {mutableStateOf<Float?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var apk by remember {mutableStateOf<File?>(null)}
    suspend fun check(manual:Boolean) {
        if(busy) return
        busy=true
        try {val next=updates.check(manual);if(next?.code!=release?.code) apk=null;release=next;if(manual || release!=null) {message=if(release==null) "当前已是最新版本" else null;dialog=true}}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {if(manual) {message=e.message ?: "检查更新失败";dialog=true}}
        finally {busy=false}
    }
    LaunchedEffect(updates,lifecycle) {lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {check(false);awaitCancellation()}}
    OutlinedButton(onClick={scope.launch {check(true)}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
        OneLineText(if(busy) "正在检查更新…" else if(release!=null) "发现新版本 · "+release!!.version else "检查更新 · "+BuildConfig.VERSION_NAME)
    }
    if(dialog) AlertDialog(onDismissRequest={if(!busy) {release?.let {updates.snooze(it)};dialog=false}},
        title={Text(release?.let {"更新至 "+it.version} ?: "应用更新")},text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                release?.let {
                    Text(it.notes.ifBlank {"新版本已发布。"})
                    Text("%.1f MB".format(it.size/1024.0/1024),style=MaterialTheme.typography.bodySmall)
                    Text("覆盖安装保留本机数据，安装前请保存当前编辑。",style=MaterialTheme.typography.bodySmall)
                }
                if(busy) {progress?.let {LinearProgressIndicator(progress={it},modifier=Modifier.fillMaxWidth())} ?: LinearProgressIndicator(Modifier.fillMaxWidth())}
                message?.let {Text(it,color=MaterialTheme.colorScheme.primary)}
            }
        },confirmButton={
            val current=release
            if(current==null) TextButton(onClick={dialog=false}) {Text("知道了")}
            else TextButton(enabled=!busy,onClick={scope.launch {
                busy=true;message=null
                try {
                    val file=apk ?: updates.download(current) {value->scope.launch {progress=value}}.also {apk=it}
                    val opened=withContext(Dispatchers.IO) {updates.install(file,current)}
                    message=if(opened) "请在系统页面确认安装" else "允许安装后，返回此处点击安装"
                } catch(e:CancellationException) {throw e}
                catch(e:Exception) {message=e.message ?: "更新失败，请重试";apk=null}
                finally {busy=false;progress=null}
            }}) {Text(if(apk==null) "下载更新" else "安装更新")}
        },dismissButton={if(release!=null) TextButton(enabled=!busy,onClick={release?.let {updates.snooze(it)};dialog=false}) {Text("稍后")}})
}
