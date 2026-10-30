package dev.lzk.fitlog.ui

import androidx.compose.foundation.clickable
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
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExerciseLibraryScreen(state: WorkoutUiState, model: WorkoutViewModel, onAdd: (Exercise) -> Unit, onTemplates: () -> Unit = {}) {
    val canAdd = state.workout?.session?.status != SessionStatus.PAUSED
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(model.currentSearchQuery)) }
    var detail by remember { mutableStateOf<Exercise?>(null) }
    var custom by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf<Exercise?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OneLineText("动作库", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            TextButton(onClick = onTemplates, modifier = Modifier.testTag("open-templates")) {
                LineIcon(Symbol.LIST, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                OneLineText("模板")
            }
            TextButton(onClick = { custom = true }) { Text("自定义") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it; model.search(it.text) },
            modifier = Modifier.fillMaxWidth().testTag("exercise-search"), singleLine = true,
            placeholder = { Text("搜索动作中文名或英文名") },
            leadingIcon = { LineIcon(Symbol.SEARCH, Modifier.size(22.dp)) },
            trailingIcon = {
                if (query.text.isNotEmpty()) IconButton(
                    onClick = { query = TextFieldValue(""); model.search("") },
                    modifier = Modifier.semanticsLabel("清空搜索"),
                ) { Text("×", style = MaterialTheme.typography.titleLarge) }
            },
            shape = RoundedCornerShape(14.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenu("器械", state.equipment, EquipmentType.entries, { it.label() }, model::equipment)
            FilterMenu("部位", state.muscle, Muscle.entries, { it.label() }, model::muscle)
            FilterChip(state.favoritesOnly, { model.favorites(!state.favoritesOnly) }, label = { Text("收藏") })
            FilterChip(state.archived, { model.archived(!state.archived) }, label = { Text("已归档") })
        }
        if (!canAdd) Text("训练已暂停，请先从主页继续训练后再添加动作。", style = MaterialTheme.typography.bodySmall)
        Text("${state.exercises.size} 个动作", Modifier.padding(bottom = 10.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(state.exercises, key = { it.id }) { exercise ->
                SoftCard(Modifier.fillMaxWidth().clickable { detail = exercise }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(ExerciseAnimations.has(exercise.id)) ExerciseGif(exercise.id,exercise.nameZh,Modifier.size(72.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(exercise.nameZh, style = MaterialTheme.typography.titleMedium)
                            Text("${exercise.equipmentType.label()} · ${exercise.primaryMuscles.joinToString("、") { it.label() }}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            if (exercise.isFavorite) Text("已收藏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (!exercise.isArchived) FilledTonalIconButton(onClick = { onAdd(exercise) }, enabled = canAdd) {
                            LineIcon(Symbol.PLUS, Modifier.size(22.dp).semanticsLabel("添加${exercise.nameZh}"))
                        }
                    }
                }
            }
            if (state.exercises.isEmpty() && !state.loading) item {
                Text("没有符合条件的动作，可调整筛选或创建自定义动作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    detail?.let { exercise ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(exercise.nameZh, style = MaterialTheme.typography.headlineSmall)
                Text(exercise.nameEn, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(exercise.equipmentType.label())
                if(ExerciseAnimations.has(exercise.id)) ExerciseGif(exercise.id,exercise.nameZh,Modifier.fillMaxWidth().height(220.dp))
                exercise.instructions?.let { Text(it) }
                Row {
                    TextButton(onClick = { model.favorite(exercise); detail = null }) { Text(if (exercise.isFavorite) "取消收藏" else "收藏动作") }
                    TextButton(onClick = { detail = null; archiving = exercise }) { Text(if (exercise.isArchived) "恢复动作" else "归档动作") }
                }
                if (!exercise.isArchived) Button(enabled = canAdd, onClick = { detail = null; onAdd(exercise) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    Text(if (state.workout == null) "加入待开始训练" else "添加到本次训练")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    archiving?.let { exercise ->
        AlertDialog(onDismissRequest = { archiving = null }, title = { Text(if (exercise.isArchived) "恢复动作？" else "归档动作？") },
            text = { Text("已有训练记录与名称快照会保留。归档动作可在“已归档”筛选中恢复。") },
            confirmButton = { TextButton(onClick = { model.archive(exercise); archiving = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text("取消") } })
    }
    if (custom) CustomExerciseDialog(onDismiss = { custom = false }) { model.createCustom(it); custom = false }
}

@Composable
private fun <T> FilterMenu(title: String, selected: T?, entries: List<T>, label: (T) -> String, onSelect: (T?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selected?.let(label) ?: title) }
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
