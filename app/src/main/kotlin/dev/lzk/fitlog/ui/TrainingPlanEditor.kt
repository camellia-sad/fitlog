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
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingPlanEditor(initial: TrainingPlan, templates: List<WorkoutTemplate>, saving: Boolean, message: String?,
    onBack: () -> Unit, onSave: (TrainingPlan) -> Unit) {
    var start by remember(initial) { mutableStateOf(initial.startDate) }
    var length by remember(initial) { mutableStateOf(initial.cycleDays.toString()) }
    var days by remember(initial) { mutableStateOf(List(14) { initial.templateIds.getOrNull(it) }) }
    var repeats by remember(initial) { mutableStateOf(initial.repeats) }
    var selecting by remember { mutableStateOf<Int?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun resize(value: String) {
        length = value
    }
    Column(Modifier.fillMaxSize()) {
        EditorHeader("安排训练周期", "保存计划", dirty = false, saving = saving, onCancel = onBack, onSave = {
            try {
                val count = length.toIntOrNull() ?: throw IllegalArgumentException("请输入整数天数")
                val value = TrainingPlan(start, count, repeats, days.take(count.coerceAtLeast(0)))
                value.validate(); error = null; onSave(value)
            } catch (e: Exception) { error = e.message }
        })
        LazyColumn(Modifier.testTag("plan-editor"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("每一天可安排一个模板，也可以留作休息。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(listOf(7, 14), { length == it.toString() }, { if (it == 7) "一周" else "两周" }) { resize(it.toString()) }
                OutlinedTextField(length, ::resize, label = { Text("周期天数（1～14）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("循环执行", Modifier.padding(top = 12.dp))
                    Switch(repeats, { repeats = it }, modifier = Modifier.semanticsLabel("循环执行"))
                }
                TextButton(onClick = { pickingDate = true }) { Text("开始日期：$start") }
                (error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items((0 until (length.toIntOrNull()?.takeIf { it in 1..14 } ?: initial.cycleDays)).toList(), key = { it }) { index ->
                SoftCard {
                    Text("第 ${index + 1} 天 · ${start.plusDays(index.toLong()).format(DateTimeFormatter.ofPattern("E", Locale.SIMPLIFIED_CHINESE))}")
                    OutlinedButton(onClick = { selecting = index }, modifier = Modifier.fillMaxWidth().testTag("plan-day-$index")) {
                        Text(days[index]?.let { id -> templates.find { it.id == id }?.name ?: "模板已删除" } ?: "休息 / 不安排")
                    }
                }
            }
        }
    }
    selecting?.let { index ->
        AlertDialog(onDismissRequest = { selecting = null }, title = { Text("第 ${index + 1} 天的模板") },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { TextButton(onClick = { days = days.toMutableList().apply { set(index, null) }; selecting = null }) { Text("休息 / 不安排") } }
                items(templates, key = { it.id }) { template ->
                    TextButton(onClick = { days = days.toMutableList().apply { set(index, template.id) }; selecting = null },
                        modifier = Modifier.fillMaxWidth()) { Text(template.name) }
                }
            } }, confirmButton = { TextButton(onClick = { selecting = null }) { Text("关闭") } })
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickingDate = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { start = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                pickingDate = false
            }) { Text("选择日期") } },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("取消") } }) { DatePicker(picker) }
    }
}
