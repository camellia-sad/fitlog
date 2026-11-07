package dev.lzk.fitlog.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun PersonalDataDeleteButton(enabled:Boolean=true,clearData: (() -> Boolean)? = null) {
    val context = LocalContext.current
    var confirming by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // The last and widest removal: an outlined pixel key in the error colour.
    val error = MaterialTheme.colorScheme.error
    PixelKey(onClick = { confirming = true; failed = false }, enabled = enabled, face = MaterialTheme.colorScheme.surface, shadow = error, outline = error,
        contentColor = error, minHeight = 44.dp, fillWidth = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        LineIcon(Symbol.TRASH, Modifier.size(14.dp), error); Text("清除全部本机数据", maxLines = 1)
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("删除全部本机信息？") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("清除本机训练记录、模板、计划、自定义动作、聊天记录、图片、登录状态、偏好、数据库及缓存。应用将关闭，重新打开后从首次设置开始。")
            Text("此操作不注销账号，云端账号、待送达消息、动态、已共享训练和云端备份会保留。已导出的备份文件需自行删除。此操作无法撤销。")
            if (failed) Text("系统未能清除数据，请稍后重试。", color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = {
            failed = !(clearData?.invoke() ?: (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData())
        }) { Text("永久删除") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } })
}
