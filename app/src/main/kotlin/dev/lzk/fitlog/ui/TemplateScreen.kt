package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.TemplateViewModel
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateScreen(model: TemplateViewModel, canStart: Boolean, onBack: () -> Unit, onExitEditor: () -> Unit, onStart: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val editingDraft by model.editing.collectAsStateWithLifecycle()
    val plan by model.plan.collectAsStateWithLifecycle()
    val planDraft by model.planEditor.collectAsStateWithLifecycle()
    planDraft?.let { draft ->
        BackHandler { model.cancelPlanEdit() }
        TrainingPlanEditor(draft, state.templates, saving, message, model::cancelPlanEdit, model::savePlan)
        return
    }
    var deleting by remember { mutableStateOf<WorkoutTemplate?>(null) }
    editingDraft?.let { draft ->
        BackHandler(onBack = onExitEditor)
        TemplateEditor(draft, state.exercises, state.preferences.weightUnit, saving, message, onExitEditor, model::updateDraft) { model.save(it) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semanticsLabel("返回动作库")) {
                LineIcon(Symbol.BACK, Modifier.size(24.dp))
            }
            OneLineText("训练模板", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = model::create, enabled = !saving) { Text("新建") }
        }
        LazyColumn(Modifier.weight(1f).testTag("template-list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                if (!canStart) Text("已有未结束训练，可先返回今日继续。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            items(state.drafts, key = { "draft:" + (it.id ?: "new") }) { draft ->
                SoftCard {
                    Text("模板草稿", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    OneLineText(draft.name.ifBlank { "未命名模板" }, style = MaterialTheme.typography.titleLarge)
                    Text("${draft.exercises.size} 个动作 · 尚未保存为模板", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { model.resumeDraft(draft) }, enabled = !saving,
                        modifier = Modifier.testTag("resume-template-draft-" + (draft.id ?: "new"))) { Text("继续编辑草稿") }
                }
            }
            item { TrainingPlanCard(plan, state.templates, model::editPlan, model::deletePlan) }
            items(state.templates, key = { "template:" + it.id }) { template ->
                SoftCard {
                    OneLineText(template.name, style = MaterialTheme.typography.titleLarge)
                    Text("${template.exercises.size} 个动作 · ${template.splitType.label()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(template.exercises.joinToString(" · ") { item -> state.exercises.find { it.id == item.exerciseId }?.nameZh ?: "已归档动作" },
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onStart(template.id) }, enabled = canStart) { Text("用于本次训练") }
                        TextButton(onClick = { model.edit(template) }, enabled = !saving) { Text("编辑") }
                        TextButton(onClick = { deleting = template }) { Text("删除") }
                    }
                }
            }
            if (state.templates.isEmpty()) item {
                SoftCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OneLineText("还没有训练模板", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        PixelPuppy(Modifier.size(48.dp))
                    }
                    Text("添加自己的动作、顺序和训练目标。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = model::create, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("创建第一个模板")
                    }
                }
            }
        }
    }
    deleting?.let { template ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除模板？") },
            text = { Text("删除“${template.name}”后，已有训练快照和记录会继续保留。") },
            confirmButton = { TextButton(onClick = { model.delete(template.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TemplateEditor(draft: TemplateDraft, exercises: List<Exercise>, unit: WeightUnit, saving: Boolean,
    message: String?, onBack: () -> Unit, onChange: (TemplateDraft) -> Unit, onSave: (TemplateDraft) -> Unit) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.name)) }
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var selecting by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TemplateExercise?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !saving) { Text("取消") }
            OneLineText("编辑模板", Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                try { draft.validate(); error = null; onSave(draft) } catch (e: Exception) { error = e.message }
            }, enabled = !saving) { Text(if (saving) "保存中…" else "保存模板", maxLines = 1) }
        }
        LazyColumn(Modifier.testTag("template-editor"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(name, { name = it; onChange(draft.copy(name = it.text)) }, label = { Text("模板名称") },
                    singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                ChoiceChips(SplitType.entries, { it == draft.splitType }, { it.label() }) { onChange(draft.copy(splitType = it)) }
                (error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(draft.exercises, key = { it.id }) { item ->
                val index = draft.exercises.indexOf(item)
                val metric=exercises.find {it.id==item.exerciseId}?.recordMetric ?: RecordMetric.REPETITIONS
                SoftCard {
                    Text(exercises.find { it.id == item.exerciseId }?.nameZh ?: "已归档动作", style = MaterialTheme.typography.titleMedium)
                    Text("${item.targetSetCount} 组 × ${item.targetRepMin}–${item.targetRepMax} ${metric.unit}"+
                        (if(metric==RecordMetric.SECONDS) "" else " · ${WeightUnits.format(item.defaultWeightGrams, unit)} ${unit.label}")+" · 休息 ${item.restSeconds} 秒",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = { editing = item }, enabled = !saving) { Text("调整") }
                        TextButton(onClick = { onChange(draft.copy(exercises = draft.exercises.moved(index, index - 1))) },
                            enabled = !saving && index > 0) { Text("上移") }
                        TextButton(onClick = { onChange(draft.copy(exercises = draft.exercises.moved(index, index + 1))) },
                            enabled = !saving && index < draft.exercises.lastIndex) { Text("下移") }
                        TextButton(onClick = { onChange(draft.copy(exercises = draft.exercises.filterNot { it.id == item.id })) },
                            enabled = !saving) { Text("移除") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { selecting = true }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("添加模板动作") }
                Text("只修改模板目标，不会改动已开始的训练。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (selecting) {
        val matches = remember(exercises, search.text) {
            val query = search.text.trim()
            exercises.filter { it.nameZh.contains(query, ignoreCase = true) || it.nameEn.contains(query, ignoreCase = true) }
        }
        ModalBottomSheet(onDismissRequest = { selecting = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OneLineText("添加模板动作", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().testTag("template-exercise-search"),
                    singleLine = true, placeholder = { Text("搜索动作中文名或英文名") },
                    leadingIcon = { LineIcon(Symbol.SEARCH, Modifier.size(22.dp)) },
                    trailingIcon = {
                        if (search.text.isNotEmpty()) IconButton(onClick = { search = TextFieldValue("") },
                            modifier = Modifier.semanticsLabel("清空模板动作搜索")) { Text("×", style = MaterialTheme.typography.titleLarge) }
                    })
                Text("${matches.size} 个动作", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f, fill = false).testTag("template-exercise-picker"),
                    contentPadding = PaddingValues(bottom = 20.dp)) {
                    if (matches.isEmpty()) item {
                        Text("没有符合名称的动作，试试其他关键词。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(matches, key = { it.id }) { exercise ->
                        TextButton(onClick = {
                            onChange(draft.copy(exercises = draft.exercises + TemplateExercise(UUID.randomUUID().toString(), exercise.id, draft.exercises.size,
                                targetRepMin=exercise.recordMetric.defaultMin,targetRepMax=exercise.recordMetric.defaultMax)))
                            selecting = false
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(exercise.nameZh, maxLines = 1) }
                    }
                }
            }
        }
    }
    editing?.let { item ->
        TemplateItemDialog(item, unit,exercises.find {it.id==item.exerciseId}?.recordMetric ?: RecordMetric.REPETITIONS, onDismiss = { editing = null }) { updated ->
            onChange(draft.copy(exercises = draft.exercises.map { if (it.id == updated.id) updated else it }))
            editing = null
        }
    }
}

@Composable
fun TemplateDraftExitDialog(saving: Boolean, message: String?, onKeep: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!saving) onContinue() }, title = { OneLineText("模板还未保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("保留草稿后，可以在模板页继续编辑；保存为模板后才能用于训练。")
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                Button(onClick = onKeep, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("保留草稿并退出", maxLines = 1) }
                TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("不保存并退出", maxLines = 1) }
                TextButton(onClick = onContinue, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("继续编辑", maxLines = 1) }
            }
        })
}

@Composable
private fun TemplateItemDialog(item: TemplateExercise, unit: WeightUnit,metric:RecordMetric, onDismiss: () -> Unit, onSave: (TemplateExercise) -> Unit) {
    var count by remember { mutableStateOf(item.targetSetCount.toString()) }
    var min by remember { mutableStateOf(item.targetRepMin.toString()) }
    var max by remember { mutableStateOf(item.targetRepMax.toString()) }
    var weight by remember { mutableStateOf(WeightUnits.format(item.defaultWeightGrams, unit, editing = true)) }
    var rest by remember { mutableStateOf(item.restSeconds.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("动作目标") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(count, { count = it }, label = { Text("目标组数") })
            OutlinedTextField(min, { min = it }, label = { Text(if(metric==RecordMetric.SECONDS) "最少秒数" else "最少次数") })
            OutlinedTextField(max, { max = it }, label = { Text(if(metric==RecordMetric.SECONDS) "最多秒数" else "最多次数") })
            if(metric==RecordMetric.REPETITIONS) OutlinedTextField(weight, { weight = it }, label = { Text("默认重量（${unit.label}）") })
            OutlinedTextField(rest, { rest = it }, label = { Text("休息秒数") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val updated = item.copy(targetSetCount = count.toInt(), targetRepMin = min.toInt(), targetRepMax = max.toInt(),
                defaultWeightGrams = if(metric==RecordMetric.SECONDS) 0L else WeightUnits.parse(weight, unit), restSeconds = rest.toInt())
            TemplateDraft(name = "校验目标", exercises = listOf(updated)).validate()
            onSave(updated)
        } catch (e: Exception) { error = "请输入有效目标：组数 1～100、正数次数范围、非负重量和 0～3600 秒休息" }
    }) { Text("确认目标") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { if (to in indices) add(to, removeAt(from)) }
