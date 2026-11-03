package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.lzk.fitlog.domain.*

@Composable
fun ExerciseHistoryScreen(exercise: Exercise?, records: List<Workout>, unit: WeightUnit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semanticsLabel("返回动作库")) { LineIcon(Symbol.BACK, Modifier.size(24.dp)) }
            OneLineText(exercise?.nameZh ?: "动作历史", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val points = remember(records, exercise?.id) { exercise?.let { TrainingHistory.performance(records, it.id) }.orEmpty() }
            PerformanceChart(points, unit, exercise?.recordMetric ?: RecordMetric.REPETITIONS)
        }
    }
}
