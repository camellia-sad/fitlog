package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*

@Composable
fun ExerciseWeightControl(exercise: Exercise, unit: WeightUnit, onSave: (Long?) -> Unit) {
    var editing by remember(exercise.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("动作重量", style = MaterialTheme.typography.titleMedium)
        OneLineText("默认重量 · " + (exercise.defaultWeightGrams?.let { WeightUnits.format(it, unit) + " " + unit.label } ?: "尚未记录"),
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("已完成最高 · " + (exercise.bestWeightGrams?.let { WeightUnits.format(it, unit) + " " + unit.label } ?: "尚未记录"),
            style = MaterialTheme.typography.bodySmall)
        Text(if(exercise.hasManualWeight) "使用手动重量；完成新的最高重量后自动更新。" else "使用已完成组的最高重量，可手动调整。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-exercise-weight")) { Text("设置默认重量") }
    }
    if(editing) {
        var text by remember { mutableStateOf(exercise.defaultWeightGrams?.let { WeightUnits.format(it, unit, editing = true) } ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editing = false }, title = { OneLineText("动作默认重量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { text = it }, label = { Text("重量（${unit.label}）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("exercise-default-weight"))
                    Text("用于下次添加动作；不会修改当前训练或模板的固定重量。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onSave(null); editing = false }) { Text("跟随最高重量") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = {
                try {
                    val weight = WeightUnits.parse(text, unit)
                    WorkoutRules.draft(weight, 0)
                    onSave(weight); editing = false
                } catch(e: Exception) { error = e.message ?: "请输入有效重量" }
            }) { Text("保存重量") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}
