package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import java.time.Instant
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: WorkoutUiState, onStart: () -> Unit, onContinue: () -> Unit, onLibrary: () -> Unit,
    weekly: List<Workout> = emptyList(), templates: List<WorkoutTemplate> = emptyList(),
    onHistory: () -> Unit = {}, selectedTemplateId: String? = null, onSelectTemplate: (String?) -> Unit = {},
    prepared: List<Exercise> = emptyList(), onRemovePrepared: (String) -> Unit = {}, plan: TrainingPlan? = null) {
    val workout = state.workout
    var pickingTemplate by remember { mutableStateOf(false) }
    val selectedTemplate = templates.find { it.id == selectedTemplateId }
    val now by produceState(Instant.now(), workout?.session?.id) { while (true) { value = Instant.now(); delay(1_000) } }
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OneLineText("FitLog", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                OneLineText("今天也很棒！", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!largeFont) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                Text("慢慢变强", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
            PixelPuppy(Modifier.size(if (largeFont) 56.dp else 82.dp), if (workout?.session?.status == SessionStatus.PAUSED) PuppyPose.SLEEP else PuppyPose.IDLE)
        }

        SoftCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OneLineText("今日训练", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    OneLineText(LocalDate.now().format(DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onHistory, modifier = Modifier.testTag("open-history"),
                    contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(12.dp)) {
                    LineIcon(Symbol.CLOCK, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    OneLineText("历史", style = MaterialTheme.typography.labelLarge)
                }
            }
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                            LineIcon(Symbol.DUMBBELL, Modifier.padding(14.dp).size(28.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OneLineText(if (workout == null) "开始今天的训练" else workout.session.trainingLabel,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                            OneLineText(if (workout == null) "选择自由训练或自己的模板" else "${if (workout.session.status == SessionStatus.PAUSED) "已暂停" else "训练中"} · ${workout.elapsedDuration(now).timerText()}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (workout == null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedTemplateId == null, onClick = { onSelectTemplate(null) }, label = { Text("自由训练") })
                            OutlinedButton(onClick = { pickingTemplate = true }) { OneLineText(selectedTemplate?.name ?: "模板训练") }
                        }
                        plan?.dayIndex(LocalDate.now())?.let { index ->
                            val id = plan.templateIds[index]
                            Text("今日计划：" + (if (id == null) "休息，可按需自由训练" else templates.find { it.id == id }?.name ?: "模板已删除，请重新安排"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selectedTemplateId == null) {
                            prepared.forEach { exercise ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(exercise.nameZh, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { onRemovePrepared(exercise.id) }) { Text("移除") }
                                }
                            }
                            OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth()) { Text("添加本次动作") }
                        }
                    }
                    Button(onClick = if (workout == null) onStart else onContinue,
                        enabled = !state.loading && (selectedTemplateId == null || selectedTemplate != null || workout != null), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        shape = RoundedCornerShape(14.dp)) {
                        LineIcon(if (workout == null) Symbol.PLUS else Symbol.ARROW, Modifier.size(22.dp), MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp))
                        Text(if (workout == null) "开始训练" else "继续训练", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        val dates = TrainingHistory.days(weekly).associateBy { it.date }
        if (dates.isNotEmpty()) SoftCard {
            val monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            Text("本周训练 · ${dates.size} / 7 天", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(7) { index ->
                    val date = monday.plusDays(index.toLong())
                    val done = (dates[date]?.completedSets ?: 0) > 0
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(6.dp), modifier = Modifier.size(30.dp)) {
                            if (done) LineIcon(Symbol.CHECK, Modifier.padding(6.dp), MaterialTheme.colorScheme.onPrimary)
                        }
                        Text(listOf("一","二","三","四","五","六","日")[index], style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (workout != null && workout.exercises.isNotEmpty()) {
            SoftCard {
                Row(Modifier.fillMaxWidth()) {
                    Text("本次进度", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("${workout.completedSetCount} / ${workout.plannedSetCount} 组", color = MaterialTheme.colorScheme.primary)
                }
                PixelProgress(workout.completedSetCount, workout.plannedSetCount, Modifier.fillMaxWidth())
                Text(if (workout.exercises.isEmpty()) "先选一个熟悉的动作吧。" else "已经完成 ${workout.completedExerciseCount} 个动作，按自己的节奏继续。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        } else if (workout == null) {
            SoftCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (dates.isEmpty()) "本周还没有训练记录" else "找到今天想练的动作", fontWeight = FontWeight.Medium)
                        Text("添加动作，开始自己的训练。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalIconButton(onClick = onLibrary) { LineIcon(Symbol.ARROW, Modifier.size(22.dp)) }
                }
            }
        }

        SoftCard(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelPuppy(Modifier.size(66.dp), when {
                    workout?.session?.status == SessionStatus.PAUSED -> PuppyPose.SLEEP
                    (dates[LocalDate.now()]?.completedSets ?: 0) > 0 -> PuppyPose.CELEBRATE
                    else -> PuppyPose.REST
                })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("每一组，都算数。", fontWeight = FontWeight.Medium)
                    OneLineText("记下进步，下次接着来。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    if (pickingTemplate) AlertDialog(onDismissRequest = { pickingTemplate = false }, title = { Text("选择训练模板") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            if (templates.isEmpty()) Text("还没有模板，可到动作页创建自己的训练安排。")
            templates.forEach { template ->
                TextButton(onClick = { onSelectTemplate(template.id); pickingTemplate = false }, modifier = Modifier.fillMaxWidth()) { Text(template.name) }
            }
        } }, confirmButton = { TextButton(onClick = { pickingTemplate = false }) { Text("关闭") } })
}