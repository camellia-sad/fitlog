package dev.lzk.fitlog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.TemplateViewModel
import java.time.LocalDate

/** Template page: the plan timetable taped on top, templates as sticky notes pinned to the board below. */
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
        TemplateEditor(draft, state.templates, state.exercises, state.preferences, dirty = model.hasUnsavedChanges, saving = saving, message = message,
            onBack = onExitEditor, onChange = model::updateDraft) { model.save(it) }
        return
    }
    val shortNames = remember(state.templates) { state.templates.shortNames() }
    val plannedId = plan?.templateOn(LocalDate.now())
    Column(Modifier.fillMaxSize()) {
        FitTopBar("训练模板", onBack, backLabel = "返回动作库") {
            PixelKey(onClick = model::create, enabled = !saving, minHeight = 34.dp, modifier = Modifier.padding(end = 12.dp).testTag("new-template")) {
                LineIcon(Symbol.PLUS, Modifier.size(13.dp), LocalContentColor.current); Text("新建模板", maxLines = 1)
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("template-list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "notice") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!canStart) Text("已有未结束训练，可先返回今日继续。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
            item(key = "plan") { PlanTimetable(plan, state.templates, shortNames, state.exercises, model::editPlan, model::deletePlan) }
            item(key = "board") {
                NoticeBoard(state.templates.size) {
                    var slot = 0
                    state.drafts.forEach { draft ->
                        DraftNote(draft, saving, onResume = { model.resumeDraft(draft) }, modifier = Modifier.staggered(slot++))
                    }
                    state.templates.forEach { template ->
                        StickyNote(template, state.exercises, shortNames[template.id] ?: "?", planned = template.id == plannedId,
                            canStart = canStart, busy = saving, onStart = { onStart(template.id) }, onEdit = { model.edit(template) },
                            onDelete = { deleting = template }, modifier = Modifier.staggered(slot++))
                    }
                    if (state.templates.isEmpty()) EmptyBoard(saving, model::create)
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

@Composable
private fun EmptyBoard(saving: Boolean, onCreate: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OneLineText("还没有训练模板", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            PixelPuppy(Modifier.size(48.dp))
        }
        Text("添加自己的动作、顺序和训练目标，保存后钉在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PixelButton(onClick = onCreate, enabled = !saving, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) {
            LineIcon(Symbol.PLUS, Modifier.size(13.dp), LocalContentColor.current); Text("创建第一个模板")
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
