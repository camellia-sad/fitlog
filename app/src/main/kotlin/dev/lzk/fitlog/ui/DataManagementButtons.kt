package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Each removal states what goes and what stays, so the consequence reads at a glance in the confirmation. */
private enum class DataRemoval(val label: String, val symbol: Symbol, val removes: List<String>, val keeps: List<String>, val extra: String? = null) {
    TRAINING("清空训练记录", Symbol.LIST,
        listOf("本机全部训练历史", "进行中的训练，并停止计时"),
        listOf("模板、计划与自定义动作", "收藏、偏好与聊天", "云端已共享训练和云端备份")),
    CHAT("清空聊天记录", Symbol.CHAT,
        listOf("当前账号在本机的聊天内容", "待发送消息与聊天图片"),
        listOf("好友、动态与动态图片", "对方的记录与云端待送达消息", "旧备份")),
    ACCOUNT("注销账号", Symbol.PERSON,
        listOf("云端账号与好友关系", "动态、评论、点赞与共享训练", "云端图片与云端备份", "本机该账号的聊天与社交图片"),
        listOf("本机训练历史、模板、计划", "自定义动作与偏好"),
        "可用原邮箱注册新账号；已导出的文件及他人设备上的副本无法一并删除。"),
}

@Composable
internal fun DataManagementButtons(loggedIn: Boolean, busy: Boolean, onTraining: () -> Unit, onChats: () -> Unit, onAccount: () -> Unit) {
    var selected by remember { mutableStateOf<DataRemoval?>(null) }
    var confirmation by remember { mutableStateOf("") }
    Column {
        DataRemoval.entries.forEach { action ->
            val available = action == DataRemoval.TRAINING || loggedIn
            ListRow(action.symbol, action.label, if (available) null else "登录后可用", tone = RowTone.DANGER, enabled = !busy && available) {
                selected = action; confirmation = ""
            }
            HorizontalDivider(color = lerp(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.outlineVariant, .8f))
        }
        if (busy) Text("正在处理，请稍候。", Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
    }
    selected?.let { action ->
        val scheme = MaterialTheme.colorScheme
        val ready = !busy && (action != DataRemoval.ACCOUNT || confirmation == "注销")
        AlertDialog(onDismissRequest = { if (!busy) selected = null },
            icon = { Box(Modifier.size(38.dp).background(scheme.errorContainer, PixelShapeSmall), contentAlignment = Alignment.Center) { LineIcon(action.symbol, Modifier.size(18.dp), scheme.error) } },
            title = { Text(action.label + "？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    action.removes.forEach { Consequence("会删除", it, scheme.error) }
                    action.keeps.forEach { Consequence("会保留", it, scheme.primary) }
                    Text(listOfNotNull(action.extra, "此操作无法撤销。").joinToString(""), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                    if (action == DataRemoval.ACCOUNT) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("输入“注销”确认") }, singleLine = true)
                }
            },
            confirmButton = {
                PixelKey(onClick = {
                    selected = null
                    when (action) { DataRemoval.TRAINING -> onTraining(); DataRemoval.CHAT -> onChats(); DataRemoval.ACCOUNT -> onAccount() }
                }, enabled = ready, face = scheme.error, shadow = lerp(scheme.error, Color.Black, .35f), contentColor = scheme.onError) { Text("确认" + action.label, maxLines = 1) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text("取消") } })
    }
}

@Composable
private fun Consequence(kind: String, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kind, Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
