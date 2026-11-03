package dev.lzk.fitlog.features

import android.content.Context
import android.net.Uri
import androidx.lifecycle.*
import dev.lzk.fitlog.AppContainer
import dev.lzk.fitlog.data.JsonBackupRepository
import dev.lzk.fitlog.domain.*
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

open class FeatureModel(protected val container: AppContainer) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    protected fun execute(action: suspend () -> Unit) = viewModelScope.launch {
        try { container.ready.await(); action() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { message.value = error.message ?: "操作失败，请重试" }
    }
}

data class TemplateUiState(
    val templates: List<WorkoutTemplate> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val drafts: List<TemplateDraft> = emptyList(),
)

class TemplateViewModel(container: AppContainer) : FeatureModel(container) {
    private val editor = MutableStateFlow<TemplateDraft?>(null)
    private var original = TemplateDraft()
    val editing = editor.asStateFlow()
    val hasUnsavedChanges: Boolean get() = editor.value?.let { it != original } ?: false
    val saving = MutableStateFlow(false)
    val plan = container.training.observePlan().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val planEditor = MutableStateFlow<TrainingPlan?>(null)
    fun editPlan() { message.value = null; planEditor.value = plan.value ?: TrainingPlan(LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))) }
    fun cancelPlanEdit() { if (!saving.value) planEditor.value = null }
    fun savePlan(value: TrainingPlan) = execute {
        if (saving.value) return@execute
        saving.value = true
        try { container.training.savePlan(value); planEditor.value = null; message.value = "计划已保存" }
        finally { saving.value = false }
    }
    fun deletePlan() = execute { container.training.deletePlan(); message.value = "计划已移除" }
    val state = combine(container.training.observeTemplates(), container.workouts.observeExercises(),
        container.preferences.preferences, container.training.observeTemplateDrafts()) { templates, exercises, preferences, drafts ->
        TemplateUiState(templates, exercises, preferences, drafts)
    }.catch { message.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TemplateUiState())

    private suspend fun openEditor(value: TemplateDraft) {
        if (saving.value || editor.value != null) return
        saving.value = true
        try {
            val retained = container.training.observeTemplateDrafts().first().find { it.id == value.id }
            original = value
            editor.value = retained ?: value
            message.value = null
        } finally { saving.value = false }
    }

    fun create() = execute { openEditor(TemplateDraft()) }
    fun edit(template: WorkoutTemplate) = execute {
        openEditor(TemplateDraft(template.id, template.name, template.splitType, template.exercises))
    }
    fun resumeDraft(value: TemplateDraft) = execute {
        val template = value.id?.let { id -> container.training.observeTemplates().first().find { it.id == id } }
        if (value.id != null && template == null) error("原模板已删除")
        openEditor(template?.let { TemplateDraft(it.id, it.name, it.splitType, it.exercises) } ?: TemplateDraft())
    }
    fun updateDraft(value: TemplateDraft) {
        if (!saving.value && editor.value != null) editor.value = value
    }
    fun closeEditor() { if (!saving.value) editor.value = null }
    fun keepDraft() = execute {
        val value = editor.value ?: return@execute
        if (saving.value) return@execute
        saving.value = true
        try {
            container.training.saveTemplateDraft(value)
            editor.value = null
            message.value = "草稿已保留"
        } finally { saving.value = false }
    }
    fun discardDraft() = execute {
        val value = editor.value ?: return@execute
        if (saving.value) return@execute
        saving.value = true
        try {
            container.training.deleteTemplateDraft(value.id)
            editor.value = null
            message.value = null
        } finally { saving.value = false }
    }
    fun save(draft: TemplateDraft) = execute {
        if (saving.value || editor.value == null) return@execute
        saving.value = true
        try {
            SaveTemplateUseCase(container.training)(draft)
            editor.value = null
            message.value = "模板已保存"
        } finally { saving.value = false }
    }
    fun delete(id: String) = execute { container.training.deleteTemplate(id); message.value = "模板已删除" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(container: AppContainer) : FeatureModel(container) {
    val clearingDate = MutableStateFlow(false)
    val clearNotice = MutableStateFlow<String?>(null)
    fun clearDate(date: LocalDate) = execute {
        if(clearingDate.value) return@execute
        clearingDate.value = true; message.value = null; clearNotice.value = null
        try {
            val count = container.training.clearTrainingDate(date)
            clearNotice.value = "已清除 $date 的 $count 条本机训练记录"
        } finally { clearingDate.value = false }
    }
    val year = MutableStateFlow(LocalDate.now().year)
    val selectedDate = MutableStateFlow(LocalDate.now())
    val selectedExerciseId = MutableStateFlow<String?>(null)
    val history = year.flatMapLatest { container.training.observeHistory(LocalDate.of(it, 1, 1), LocalDate.of(it, 12, 31)) }
        .catch { message.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val performance = selectedExerciseId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else container.training.observeExerciseHistory(id)
    }.catch { message.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun selectDate(date: LocalDate) { selectedDate.value = date; year.value = date.year; clearNotice.value = null }
    fun setYear(value: Int) { year.value = value; selectedDate.value = selectedDate.value.withYear(value); clearNotice.value = null }
}

class SettingsViewModel(container: AppContainer) : FeatureModel(container) {
    val profile=container.friends.profile
    fun clearTraining() = transfer {
        container.training.clearTrainingHistory()
        message.value="已清空本机训练记录，模板、计划和动作已保留"
    }
    fun clearChats(onDone:()->Unit={}) = transfer {
        container.social.clearAllChats();onDone()
        message.value="已清空当前账号的本机聊天记录与对应图片"
    }
    fun deleteAccount(onDone:()->Unit={}) = transfer {
        container.social.deleteAccount();onDone()
        message.value="账号已注销，离线训练数据已保留；可使用原邮箱重新注册"
    }
    val preferences = container.preferences.preferences
        .catch { message.value = it.message }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())
    val busy = MutableStateFlow(false)
    fun save(value: UserPreferences) = execute { container.preferences.save(value.copy(onboardingCompleted = true)); message.value = "设置已保存" }

    fun exportNetworkLogs(context: Context, uri: Uri) = transfer {
        withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开导出位置" }
                .bufferedWriter().use { it.write(container.networkDiagnostics.exportJson()) }
        }
        message.value = "网络调试日志已导出"
    }
    fun clearNetworkLogs() = transfer {
        withContext(Dispatchers.IO) { container.networkDiagnostics.clear() }
        message.value = "本机网络调试日志已清空"
    }

    fun export(context: Context, uri: Uri) = transfer {
        val text = container.portableBackups.exportLocalJson()
        withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开导出位置" }
                .bufferedWriter().use { it.write(text) }
        }
        message.value = "训练与模板备份已导出"
    }

    fun import(context: Context, uri: Uri) = transfer {
        val text = withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开备份文件" }.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    require(total <= JsonBackupRepository.MAX_BYTES) { "备份最大支持 32 MB" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        }
        val result = withContext(Dispatchers.IO) { container.portableBackups.importLocalJson(text) }
        message.value = "导入完成：新增 ${result.sessionsAdded} 次训练、${result.templatesAdded} 个模板、${result.exercisesAdded} 个动作"
    }

    private fun transfer(action: suspend () -> Unit) = execute {
        if (busy.value) return@execute
        busy.value = true
        try { action() } finally { busy.value = false }
    }
}

class AppModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val model = when (modelClass) {
            TemplateViewModel::class.java -> TemplateViewModel(container)
            HistoryViewModel::class.java -> HistoryViewModel(container)
            SettingsViewModel::class.java -> SettingsViewModel(container)
            else -> error("未知的页面模型")
        }
        @Suppress("UNCHECKED_CAST") return model as T
    }
}
