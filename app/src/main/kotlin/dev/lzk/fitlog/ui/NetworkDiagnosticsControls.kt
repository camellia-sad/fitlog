package dev.lzk.fitlog.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun NetworkDiagnosticsControls(busy: Boolean, onExport: (Context, Uri) -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    var clearing by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) onExport(context, uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ListRow(Symbol.SETTINGS, "网络调试日志", "连接重置、超时等请求错误保存在本机：最近 7 天、最多 200 条，不自动上传", onClick = null)
        Text("导出包含设备型号、系统版本、网络类型、DNS 服务器和错误阶段；不含验证码、令牌、邮箱及聊天或训练内容。",
            Modifier.padding(start = 60.dp, end = 14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(start = 60.dp, end = 14.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelOutlineKey(onClick = { exporter.launch("FitLog-network-${LocalDate.now()}.json") }, enabled = !busy, minHeight = 34.dp,
                modifier = Modifier.testTag("export-network-logs")) {
                LineIcon(Symbol.EXPORT, Modifier.size(13.dp), MaterialTheme.colorScheme.onSurfaceVariant); Text("导出日志", maxLines = 1)
            }
            PixelOutlineKey(onClick = { clearing = true }, enabled = !busy, minHeight = 34.dp, modifier = Modifier.testTag("clear-network-logs")) {
                LineIcon(Symbol.TRASH, Modifier.size(13.dp), MaterialTheme.colorScheme.onSurfaceVariant); Text("清空日志", maxLines = 1)
            }
        }
        HorizontalDivider(color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.outlineVariant, .8f))
    }
    if(clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { OneLineText("清空网络调试日志？") },
        text = { Text("仅清空本机网络错误记录，不影响训练、聊天或账号。已导出的文件需要自行删除。") },
        confirmButton = { TextButton(onClick = { clearing = false; onClear() }, enabled = !busy) { Text("确认清空日志") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}
