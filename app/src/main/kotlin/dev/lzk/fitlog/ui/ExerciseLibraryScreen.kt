package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseLibraryScreen(state: WorkoutUiState, model: WorkoutViewModel, onAdd: (Exercise) -> Unit, onTemplates: () -> Unit = {}, onOpen: (Exercise) -> Unit = {}) {
    val canAdd = state.workout?.session?.status != SessionStatus.PAUSED
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(model.currentSearchQuery)) }
    var custom by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OneLineText("动作库", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onTemplates, modifier = Modifier.testTag("open-templates")) {
                LineIcon(Symbol.LIST, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                OneLineText("模板")
            }
            TextButton(onClick = { custom = true }) {
                LineIcon(Symbol.PLUS, Modifier.size(12.dp)); Spacer(Modifier.width(6.dp)); Text("自定义")
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it; model.search(it.text) },
            modifier = Modifier.fillMaxWidth().testTag("exercise-search"), singleLine = true,
            placeholder = { Text("搜索动作中文名或英文名") },
            leadingIcon = { LineIcon(Symbol.SEARCH, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (query.text.isNotEmpty()) IconButton(
                    onClick = { query = TextFieldValue(""); model.search("") },
                    modifier = Modifier.semanticsLabel("清空搜索"),
                ) { LineIcon(Symbol.CLOSE, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            shape = MaterialTheme.shapes.small,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.muscle == null, { model.muscle(null) }, label = { Text("全部部位") })
            Muscle.entries.forEach { muscle ->
                val group = muscle.group
                FilterChip(state.muscle == muscle, { model.muscle(if (state.muscle == muscle) null else muscle) },
                    label = { Text(muscle.label(), maxLines = 1) },
                    leadingIcon = { Box(Modifier.size(8.dp).background(group.color())) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = group.tint(), selectedLabelColor = MaterialTheme.colorScheme.onSurface),
                    border = FilterChipDefaults.filterChipBorder(true, state.muscle == muscle, selectedBorderColor = group.color(), selectedBorderWidth = 1.5.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenu("器械", state.equipment, EquipmentType.entries, { it.label() }, model::equipment)
            FilterChip(state.favoritesOnly, { model.favorites(!state.favoritesOnly) }, label = { Text("收藏") })
            FilterChip(state.archived, { model.archived(!state.archived) }, label = { Text("已归档") })
        }
        if (!canAdd) Text("训练已暂停，请先从主页继续训练后再添加动作。", style = MaterialTheme.typography.bodySmall)
        Text("${state.exercises.size} 个动作", Modifier.padding(top = 4.dp, bottom = 10.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(state.exercises, key = { it.id }) { exercise ->
                ExerciseRow(exercise, state.preferences.weightUnit, canAdd, onOpen = { onOpen(exercise) }, onAdd = { onAdd(exercise) })
            }
            if (state.exercises.isEmpty() && !state.loading) item {
                Text("没有符合条件的动作，可调整筛选或创建自定义动作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (custom) CustomExerciseDialog(onDismiss = { custom = false }) { model.createCustom(it); custom = false }
}

@Composable
private fun ExerciseRow(exercise: Exercise, unit: WeightUnit, canAdd: Boolean, onOpen: () -> Unit, onAdd: () -> Unit) {
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ExerciseAnimations.has(exercise.id)) ExerciseStage(exercise.id, exercise.nameZh, exercise.primaryMuscles.firstOrNull()?.group,
                Modifier.size(72.dp), corner = 12.dp)
            else Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exercise.nameZh, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (exercise.isFavorite) LineIcon(Symbol.STAR_FILLED, Modifier.size(13.dp).semanticsLabel("已收藏"), fitColors.orange)
                }
                Text("${exercise.equipmentType.label()} · ${exercise.primaryMuscles.joinToString("、") { it.label() }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                val best = exercise.bestWeightGrams
                // The number is the point: small label and unit, large pixel digits.
                if (exercise.recordMetric == RecordMetric.REPETITIONS && best != null && best > 0)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("最佳", Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(WeightUnits.format(best, unit), style = pixelNumber(19.sp), color = MaterialTheme.colorScheme.primary)
                        Text(unit.label, Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
            }
            if (!exercise.isArchived) PixelButton(onClick = onAdd, enabled = canAdd, minHeight = 40.dp,
                modifier = Modifier.width(48.dp).semanticsLabel("添加${exercise.nameZh}")) {
                LineIcon(Symbol.PLUS, Modifier.size(14.dp), LocalContentColor.current)
            }
        }
    }
}

@Composable
private fun <T> FilterMenu(title: String, selected: T?, entries: List<T>, label: (T) -> String, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected != null, { expanded = true }, label = { Text(selected?.let(label) ?: title) },
            trailingIcon = { LineIcon(Symbol.DOWN, Modifier.size(10.dp), MaterialTheme.colorScheme.onSurfaceVariant) })
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部$title") }, onClick = { onSelect(null); expanded = false })
            entries.forEach { entry -> DropdownMenuItem(text = { Text(label(entry)) }, onClick = { onSelect(entry); expanded = false }) }
        }
    }
}

@Composable
private fun CustomExerciseDialog(onDismiss: () -> Unit, onSave: (CustomExerciseDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var english by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var equipment by remember { mutableStateOf(EquipmentType.BODYWEIGHT) }
    var muscles by remember { mutableStateOf(emptySet<Muscle>()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义动作") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("动作名称") })
            OutlinedTextField(english, { english = it }, label = { Text("英文别名（选填）") })
            ChoiceChips(EquipmentType.entries, { it == equipment }, { it.label() }) { equipment = it }
            ChoiceChips(Muscle.entries, { it in muscles }, { it.label() }) { muscles = if (it in muscles) muscles - it else muscles + it }
            OutlinedTextField(instructions, { instructions = it }, label = { Text("说明（选填）") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try { val draft = CustomExerciseDraft(name, english, equipment, muscles, instructions); draft.validate(); onSave(draft) }
        catch (e: Exception) { error = e.message }
    }) { Text("保存动作") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
