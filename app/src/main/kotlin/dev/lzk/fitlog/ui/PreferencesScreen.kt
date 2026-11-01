package dev.lzk.fitlog.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.SettingsViewModel

private class PreferenceForm(initial: UserPreferences) {
    var value by mutableStateOf(initial)
    var minutes by mutableStateOf(initial.workoutMinutes.toString())
    var rest by mutableStateOf(initial.restSeconds.toString())
    var step by mutableStateOf(WeightUnits.format(initial.weightStepGrams, initial.weightUnit, editing = true))
    fun build(): UserPreferences = value.copy(
        workoutMinutes = minutes.toIntOrNull() ?: error("请输入整数训练时长"),
        restSeconds = rest.toIntOrNull() ?: error("请输入整数休息秒数"),
        weightStepGrams = WeightUnits.parse(step, value.weightUnit),
    ).also { it.validate() }
    fun unit(unit: WeightUnit) {
        val grams = runCatching { WeightUnits.parse(step, value.weightUnit) }.getOrDefault(value.weightStepGrams)
        value = value.copy(weightUnit = unit)
        step = WeightUnits.format(grams, unit, editing = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(items: List<T>, selected: (T) -> Boolean, label: (T) -> String, onClick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
            FilterChip(selected = selected(item), onClick = { onClick(item) }, label = { Text(label(item), maxLines = 1, softWrap = false) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun EquipmentChoices(form: PreferenceForm) {
    Text("常用器械", style = MaterialTheme.typography.titleMedium)
    ChoiceChips(EquipmentType.entries, { it in form.value.equipment }, { it.label() }) {
        form.value = form.value.copy(equipment = form.value.equipment.toggle(it))
    }
}
@Composable
private fun MuscleChoices(form: PreferenceForm) {
    Text("偏好部位", style = MaterialTheme.typography.titleMedium)
    ChoiceChips(Muscle.entries, { it in form.value.muscles }, { it.label() }) {
        form.value = form.value.copy(muscles = form.value.muscles.toggle(it))
    }
}
@Composable
private fun SplitChoices(form: PreferenceForm) {
    Text("训练方式", style = MaterialTheme.typography.titleMedium)
    ChoiceChips(SplitType.entries, { it == form.value.split }, { it.label() }) { form.value = form.value.copy(split = it) }
}
@Composable
private fun UnitChoices(form: PreferenceForm) {
    Text("重量单位", style = MaterialTheme.typography.titleMedium)
    ChoiceChips(WeightUnit.entries, { it == form.value.weightUnit }, { it.label }) { form.unit(it) }
}
@Composable
private fun NumberPreference(label: String, value: String, decimal: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}

@Composable
fun OnboardingScreen(preferences: UserPreferences, message: String? = null, onSave: (UserPreferences) -> Unit) {
    val form = remember(preferences) { PreferenceForm(preferences) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PixelPuppy(Modifier.size(74.dp))
            TextButton(onClick = { onSave(UserPreferences(onboardingCompleted = true)) }) { Text("跳过设置") }
        }
        OneLineText("一起找到你的节奏", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium)
        OneLineText("偏好可稍后在设置中修改。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${page + 1} / 5", color = MaterialTheme.colorScheme.primary)
        when (page) {
            0 -> EquipmentChoices(form)
            1 -> SplitChoices(form)
            2 -> MuscleChoices(form)
            3 -> {
                Text("每次训练多久？", style = MaterialTheme.typography.titleMedium)
                ChoiceChips(listOf(30,45,60,90), { form.minutes == it.toString() }, { "$it 分钟" }) { form.minutes = it.toString() }
                NumberPreference("自定义时长（分钟）", form.minutes) { form.minutes = it }
            }
            4 -> UnitChoices(form)
        }
        (error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) OutlinedButton(onClick = { page-- }) { Text("上一步") }
            Button(onClick = {
                try {
                    if (page == 3) form.build()
                    if (page < 4) page++ else onSave(form.build().copy(onboardingCompleted = true))
                } catch (e: Exception) { error = e.message }
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(if (page == 4) "开始使用" else "下一步") }
        }
    }
}

@Composable
fun SettingsScreen(model: SettingsViewModel,onBack:(()->Unit)?=null,onBackups:(()->Unit)?=null,onChatCleared:()->Unit={}) {
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val form = remember(preferences) { PreferenceForm(preferences) }
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.export(context, it) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.import(context, it) }
    }
    Column(Modifier.fillMaxSize().testTag("settings-scroll").verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            if(onBack!=null) IconButton(onClick=onBack) {LineIcon(Symbol.BACK,Modifier.size(24.dp).semanticsLabel("返回我的"))}
            Text("设置",style=MaterialTheme.typography.headlineMedium)
        }
        EquipmentChoices(form)
        SplitChoices(form)
        MuscleChoices(form)
        NumberPreference("每次训练时长（分钟）", form.minutes) { form.minutes = it }
        UnitChoices(form)
        NumberPreference("重量步长（${form.value.weightUnit.label}）", form.step, true) { form.step = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("完成一组后自动休息", Modifier.weight(1f).padding(top = 14.dp))
            Switch(checked = form.value.restEnabled, onCheckedChange = { form.value = form.value.copy(restEnabled = it) })
        }
        NumberPreference("默认休息时长（秒）", form.rest) { form.rest = it }
        Text("外观", style = MaterialTheme.typography.titleMedium)
        ChoiceChips(ThemeMode.entries, { it == form.value.theme }, { it.label() }) { form.value = form.value.copy(theme = it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            try { error = null; model.save(form.build()) } catch (e: Exception) { error = e.message }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy) { Text("保存设置") }
        HorizontalDivider()
        Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
        if(onBackups!=null) Button(onClick=onBackups,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("云端备份与恢复")}
        Text("JSON 包含训练、动作、模板、计划、偏好、草稿和当前账号的聊天数据区。图片保留在本机，可随云端备份恢复。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { exporter.launch("FitLog-${java.time.LocalDate.now()}.json") }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("导出 JSON 备份") }
        OutlinedButton(onClick = { confirmImport = true }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("导入 JSON 备份") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        val profile by model.profile.collectAsStateWithLifecycle()
        DataManagementButtons(profile!=null,busy,model::clearTraining,
            {model.clearChats{onChatCleared()}},
            {model.deleteAccount{AvatarImages.clear();onChatCleared()}})
        PersonalDataDeleteButton(enabled=!busy)
        Text("FitLog · 训练离线可用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("合并备份数据？") },
        text = { Text("相同记录不会重复导入，同 ID 的不同训练、模板或计划会取消整批导入。动作收藏、归档及偏好采用备份值，旧版备份不改变偏好。建议先导出本机备份。") },
        confirmButton = { TextButton(onClick = { confirmImport = false; importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("选择备份") } },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } })
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
fun SplitType.label(): String = when (this) {
    SplitType.FULL_BODY -> "全身"; SplitType.UPPER_LOWER -> "上下肢"
    SplitType.PUSH_PULL_LEGS -> "推拉腿"; SplitType.FREE -> "自由安排"
}
fun ThemeMode.label(): String = when (this) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }
