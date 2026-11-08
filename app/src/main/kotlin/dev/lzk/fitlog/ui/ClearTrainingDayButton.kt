package dev.lzk.fitlog.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.LocalDate

private data class DayClearRequest(val date: LocalDate, val count: Int, val hasUnfinished: Boolean)

@Composable
fun ClearTrainingDayButton(date: LocalDate, recordCount: Int, hasUnfinished: Boolean, busy: Boolean,
    onClear: (LocalDate) -> Unit) {
    var request by remember { mutableStateOf<DayClearRequest?>(null) }
    val error = MaterialTheme.colorScheme.error
    PixelOutlineKey(onClick = { request = DayClearRequest(date, recordCount, hasUnfinished) },
        enabled = recordCount > 0 && !busy, modifier = Modifier.testTag("history-clear-date"), edge = error, contentColor = error, minHeight = 34.dp) {
        LineIcon(Symbol.TRASH, Modifier.size(12.dp), error); Text("清除当日", maxLines = 1)
    }
    request?.let { target ->
        AlertDialog(onDismissRequest = { if(!busy) request = null }, title = { OneLineText("清除当天记录？") },
            text = {
                Text("将删除 ${target.date} 的 ${target.count} 条本机训练记录，包括当日计划进度、完成组、草稿、感想及加练。" +
                    (if(target.hasUnfinished) "\n其中有未结束训练，清除后计时会停止。" else "") +
                    "\n模板、循环计划、动作和聊天会保留。已分享动态、云端共享历史及已有备份不变。此操作无法撤销。",
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { request = null; onClear(target.date) }, enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm-clear-training-date")) { Text("确认清除") }
            },
            dismissButton = { TextButton(onClick = { request = null }, enabled = !busy) { Text("取消") } })
    }
}
