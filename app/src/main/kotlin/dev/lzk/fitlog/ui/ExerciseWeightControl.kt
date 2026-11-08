package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*

/**
 * Default weight for the next time this exercise is added. The big number is the value that will be used;
 * the tag says whether it follows the best completed set or was set by hand. −/+ step by the user's weight
 * step and save immediately; the middle button opens exact entry.
 */
@Composable
fun ExerciseWeightControl(exercise: Exercise, unit: WeightUnit, step: Long = WorkoutRules.DEFAULT_WEIGHT_STEP_GRAMS, onSave: (Long?) -> Unit) {
    var editing by remember(exercise.id) { mutableStateOf(false) }
    val haptics = fitHaptics()
    val value = exercise.defaultWeightGrams
    val fit = fitColors
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("默认重量", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            val manual = exercise.hasManualWeight
            Text(if (manual) "手动设置" else "跟随历史最高",
                Modifier.background(if (manual) fit.cream else MaterialTheme.colorScheme.primaryContainer, PixelShapeSmall).padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (manual) fit.orangeDeep else MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (value != null) {
                PixelNumber(WeightUnits.format(value, unit), size = 36.sp, color = MaterialTheme.colorScheme.primary)
                Text(unit.label, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("尚未记录", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LineIcon(Symbol.SPARK, Modifier.size(12.dp), fit.orange)
            Text("历史最高", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            exercise.bestWeightGrams?.let {
                Text(WeightUnits.format(it, unit), style = pixelNumber(16.sp), color = MaterialTheme.colorScheme.onSurface)
                Text(unit.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Text("还没有完成组", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeightStep(Symbol.MINUS, "减少默认重量", enabled = value != null && value >= step) { haptics.tick(); value?.let { onSave(it - step) } }
            OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("edit-exercise-weight")) {
                LineIcon(Symbol.EDIT, Modifier.size(14.dp)); Spacer(Modifier.width(8.dp)); OneLineText("设置默认重量")
            }
            WeightStep(Symbol.PLUS, "增加默认重量", enabled = true) { haptics.tick(); onSave((value ?: 0L) + step) }
        }
        if (exercise.hasManualWeight) TextButton(onClick = { onSave(null) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("恢复跟随历史最高") }
    }
    if (editing) {
        var text by remember { mutableStateOf(exercise.defaultWeightGrams?.let { WeightUnits.format(it, unit, editing = true) } ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editing = false }, title = { OneLineText("动作默认重量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(text, { text = it; error = null }, label = { Text("重量（${unit.label}）") }, singleLine = true,
                        textStyle = pixelNumber(20.sp), isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("exercise-default-weight"))
                    TextButton(onClick = { onSave(null); editing = false }) { Text("跟随最高重量") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = {
                try {
                    val weight = WeightUnits.parse(text, unit)
                    WorkoutRules.draft(weight, 0)
                    onSave(weight); editing = false
                } catch(e: Exception) { haptics.reject(); error = e.message ?: "请输入有效重量" }
            }) { Text("保存重量") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } })
    }
}

@Composable
private fun WeightStep(symbol: Symbol, label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = PixelShapeLarge, color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(48.dp).semanticsLabel(label)) {
        Box(contentAlignment = Alignment.Center) {
            LineIcon(symbol, Modifier.size(14.dp), if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
        }
    }
}
