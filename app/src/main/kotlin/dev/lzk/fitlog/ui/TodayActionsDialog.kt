package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*

@Composable
fun TodayActionsDialog(workout: Workout, onClose: () -> Unit, onSelect: (String) -> Unit, onAdd: () -> Unit,
    onAddSet: (String) -> Unit, onRemove: (String) -> Unit, editable: Boolean) {
    var removing by remember { mutableStateOf<SessionExercise?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { OneLineText(if(workout.session.isExtra) "加练动作" else "今日动作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if(editable) OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().testTag("add-today-action")) { Text("添加动作") }
                Text("完成 ${workout.completedSetCount}/${workout.plannedSetCount} 组", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(workout.exercises, key = { it.id }) { action ->
                        val done = action.sets.count { it.status == SetStatus.COMPLETED }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onSelect(action.id) }, enabled = !action.removedFromPlan,
                                modifier = Modifier.fillMaxWidth().testTag("select-action-" + action.exerciseId)) {
                                Column(Modifier.fillMaxWidth()) {
                                    OneLineText(action.exerciseNameSnapshot, style = MaterialTheme.typography.titleSmall)
                                    Text(if(action.removedFromPlan) "已移出计划 · 保留 $done 组" else if(done == action.sets.size) "已完成 · $done 组" else "未完成 · $done/${action.sets.size} 组",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if(editable) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { onAddSet(action.id) }, enabled = action.sets.size < WorkoutRules.MAX_SET_COUNT,
                                    modifier = Modifier.testTag("add-action-set-" + action.exerciseId)) { Text("增加一组") }
                                if(!action.removedFromPlan) TextButton(onClick = { removing = action },
                                    modifier = Modifier.testTag("remove-action-" + action.exerciseId)) { Text("移除动作") }
                            }
                            HorizontalDivider()
                        }
                    }
                    if(workout.exercises.isEmpty()) item { Text("还没有动作，可先添加。") }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("收起") } })
    removing?.let { action ->
        val done = action.sets.count { it.status == SetStatus.COMPLETED }
        AlertDialog(onDismissRequest = { removing = null }, title = { OneLineText("移除剩余目标？") },
            text = { Text("移除“${action.exerciseNameSnapshot}”的未完成目标。" + if(done > 0) "已经完成的 $done 组继续保留，也可以再增加组数。" else "这个动作没有完成组，将从今日动作中移除。") },
            confirmButton = { TextButton(onClick = { onRemove(action.id); removing = null }) { Text("确认移除动作") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}
