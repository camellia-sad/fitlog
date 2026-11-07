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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PixelWordmark(height = 28.dp)
            TextButton(onClick = { onSave(UserPreferences(onboardingCompleted = true)) }) { Text("跳过设置") }
        }
        PixelPuppy(Modifier.size(96.dp))
        OneLineText("一起找到你的节奏", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineMedium)
        OneLineText("偏好可稍后在设置中修改。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PixelProgress(page + 1, 5, Modifier.fillMaxWidth().semanticsLabel("第 ${page + 1} 步，共 5 步"))
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
            if (page > 0) OutlinedButton(onClick = { page-- }, modifier = Modifier.heightIn(min = 52.dp)) { Text("上一步") }
            PixelButton(onClick = {
                try {
                    if (page == 3) form.build()
                    if (page < 4) page++ else onSave(form.build().copy(onboardingCompleted = true))
                } catch (e: Exception) { error = e.message }
            }, modifier = Modifier.weight(1f)) { Text(if (page == 4) "开始使用" else "下一步") }
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
fun SplitType.label(): String = when (this) {
    SplitType.FULL_BODY -> "全身"; SplitType.UPPER_LOWER -> "上下肢"
    SplitType.PUSH_PULL_LEGS -> "推拉腿"; SplitType.FREE -> "自由安排"
}
fun ThemeMode.label(): String = when (this) { ThemeMode.TIME -> "时段主题"; ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }
