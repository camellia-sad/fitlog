package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.Workout

/** Asked only when the running session already has completed sets: they stay in its own record while it is paused. */
@Composable
fun SwitchTrainingDialog(running: Workout, target: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(onDismissRequest = onCancel, modifier = Modifier.testTag("switch-training"),
        title = { Text("暂停“${running.session.trainingLabel}”？") },
        text = {
            Row(Modifier.fillMaxWidth().background(scheme.surfaceVariant, MaterialTheme.shapes.small).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineIcon(Symbol.PAUSE, Modifier.size(16.dp), scheme.onSurfaceVariant)
                Text("${running.completedSetCount}", style = pixelNumber(26.sp), color = scheme.onSurface)
                Text("组已完成，保留在${running.session.trainingLabel}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            PixelOutlineKey(onClick = onCancel, minHeight = 42.dp, minWidth = 88.dp, modifier = Modifier.testTag("switch-cancel")) { Text("取消", maxLines = 1) }
        },
        confirmButton = {
            PixelKey(onClick = onConfirm, minHeight = 42.dp, minWidth = 120.dp, depth = 4.dp, modifier = Modifier.testTag("switch-confirm")) {
                LineIcon(Symbol.PLAY, Modifier.size(12.dp), LocalContentColor.current); Text("开始 $target", maxLines = 1)
            }
        })
}
