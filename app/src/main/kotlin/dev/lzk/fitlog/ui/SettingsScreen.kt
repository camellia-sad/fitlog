package dev.lzk.fitlog.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lzk.fitlog.BuildConfig
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.SettingsViewModel
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Settings: a training profile card up top states what the settings do (minutes, rest, weight step, kit),
 * then every setting is a pixel control that saves as it changes. Removal actions live in a dashed danger zone.
 */

private enum class SettingsGroup(val title: String, val symbol: Symbol, val danger: Boolean = false, val note: String? = null) {
    TRAINING("训练偏好", Symbol.DUMBBELL), REST("休息与反馈", Symbol.CLOCK), LOOK("外观", Symbol.SUN),
    SOCIAL("社交与隐私", Symbol.FRIENDS, note = "仅好友可见"),
    BACKUP("备份与恢复", Symbol.CLOUD), DATA("数据管理", Symbol.TRASH, danger = true)
}

/** Rows the profile card can jump to. */
private enum class SettingsRow { MINUTES, REST, STEP }

private val EquipmentOrder = listOf(EquipmentType.BARBELL, EquipmentType.DUMBBELL, EquipmentType.MACHINE, EquipmentType.SMITH, EquipmentType.CABLE, EquipmentType.BODYWEIGHT)

@Composable
fun SettingsScreen(model: SettingsViewModel, onBack: (() -> Unit)? = null, onBackups: (() -> Unit)? = null, onChatCleared: () -> Unit = {},
    social: (@Composable ColumnScope.() -> Unit)? = null) {
    val stored by model.preferences.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val savedCount by model.saved.collectAsStateWithLifecycle()
    val profile by model.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // The page edits a local copy and writes it shortly after each change, so held steppers don't race the store.
    var local by remember { mutableStateOf(stored) }
    var pending by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(stored) { if (pending?.isActive != true) local = stored }
    fun change(value: UserPreferences) {
        local = value
        pending?.cancel()
        pending = scope.launch { delay(250); model.update(value).join() }
    }

    val groupTops = remember { mutableStateMapOf<SettingsGroup, Int>() }
    val rowGroup = mapOf(SettingsRow.MINUTES to SettingsGroup.TRAINING, SettingsRow.STEP to SettingsGroup.TRAINING, SettingsRow.REST to SettingsGroup.REST)
    var flash by remember { mutableStateOf<Pair<SettingsRow, Int>?>(null) }
    fun jump(group: SettingsGroup) { groupTops[group]?.let { scope.launch { scroll.animateScrollTo(it) } } }

    var confirmImport by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { model.export(context, it) } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.import(context, it) } }

    Column(Modifier.fillMaxSize()) {
        FitTopBar("设置", onBack, backLabel = "返回我的") { SavedStamp(savedCount, Modifier.padding(end = 16.dp)) }
        JumpStrip { jump(it) }
        message?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Column(Modifier.weight(1f).testTag("settings-scroll").verticalScroll(scroll).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ProfileCard(local) { row -> rowGroup[row]?.let(::jump); flash = row to ((flash?.second ?: 0) + 1) }

            SettingsGroupBlock(SettingsGroup.TRAINING, { groupTops[SettingsGroup.TRAINING] = it }) {
                SettingRow("常用器械") { EquipmentTiles(local.equipment) { change(local.copy(equipment = local.equipment.toggle(it))) } }
                SettingRow("训练方式") {
                    PixelSegmented(SplitType.entries.map { it.label() }, SplitType.entries.indexOf(local.split)) { change(local.copy(split = SplitType.entries[it])) }
                }
                SettingRow("偏好部位") { MuscleChoiceChips(local.muscles) { change(local.copy(muscles = local.muscles.toggle(it))) } }
                SettingRow("每次训练时长", flash = flash.forRow(SettingsRow.MINUTES), trailing = {
                    PixelStepper(local.workoutMinutes.toLong(), 5, 360, 5, "训练时长", unit = "分钟", tag = "settings-minutes") { change(local.copy(workoutMinutes = it.toInt())) }
                }) { Presets(listOf(30, 45, 60, 90), local.workoutMinutes, "分钟") { change(local.copy(workoutMinutes = it)) } }
                val unit = local.weightUnit
                val stepUnit = WeightUnits.parse(if (unit == WeightUnit.KG) "0.25" else "0.5", unit)
                SettingRow("重量单位与步长", flash =flash.forRow(SettingsRow.STEP), trailing = {
                    PixelStepper(local.weightStepGrams, stepUnit, WeightUnits.parse("20", unit), stepUnit, "重量步长", valueWidth = 58.dp,
                        format = { WeightUnits.format(it, unit) }, unit = unit.label, tag = "settings-step") { change(local.copy(weightStepGrams = it)) }
                }) {
                    PixelSegmented(WeightUnit.entries.map { it.label }, WeightUnit.entries.indexOf(unit)) { index ->
                        val next = WeightUnit.entries[index]
                        // Switching unit moves the step to that unit's usual plate step instead of an odd converted value.
                        change(local.copy(weightUnit = next, weightStepGrams = WeightUnits.parse(if (next == WeightUnit.KG) "2.5" else "5", next)))
                    }
                    val presets = if (unit == WeightUnit.KG) listOf("1.25", "2.5", "5") else listOf("2.5", "5", "10")
                    WeightPresets(presets, unit, local.weightStepGrams) { change(local.copy(weightStepGrams = it)) }
                }
            }

            SettingsGroupBlock(SettingsGroup.REST, { groupTops[SettingsGroup.REST] = it }) {
                SettingRow("完成一组后自动休息", flash = flash.forRow(SettingsRow.REST), trailing = {
                    PixelSwitch(local.restEnabled, "完成一组后自动休息", Modifier.testTag("settings-rest-switch")) { change(local.copy(restEnabled = it)) }
                })
                if (local.restEnabled) SettingRow("默认休息时长", trailing = {
                    PixelStepper(local.restSeconds.toLong(), 0, 3600, 15, "休息时长", valueWidth = 58.dp, unit = "秒", tag = "settings-rest") { change(local.copy(restSeconds = it.toInt())) }
                }) { Presets(listOf(60, 90, 120, 180), local.restSeconds, "秒") { change(local.copy(restSeconds = it)) } }
                SettingRow("操作震动", "按键、休息结束和新纪录时轻震", trailing = {
                    PixelSwitch(local.hapticsEnabled, "操作震动", Modifier.testTag("settings-haptics-switch")) { change(local.copy(hapticsEnabled = it)) }
                })
            }

            SettingsGroupBlock(SettingsGroup.LOOK, { groupTops[SettingsGroup.LOOK] = it }) {
                SettingRow("主题") { ThemeCards(local.theme) { change(local.copy(theme = it)) } }
            }

            if (social != null) SettingsGroupBlock(SettingsGroup.SOCIAL, { groupTops[SettingsGroup.SOCIAL] = it }, content = social)

            SettingsGroupBlock(SettingsGroup.BACKUP, { groupTops[SettingsGroup.BACKUP] = it }) {
                if (onBackups != null) ListRow(Symbol.CLOUD, "云端备份与恢复", tone = RowTone.HERO, enabled = !busy, onClick = onBackups)
                ListRow(Symbol.EXPORT, "导出 JSON 备份", enabled = !busy) { exporter.launch("FitLog-${java.time.LocalDate.now()}.json") }
                ListRow(Symbol.IMPORT, "导入 JSON 备份", enabled = !busy) { confirmImport = true }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp))
            }

            SettingsGroupBlock(SettingsGroup.DATA, { groupTops[SettingsGroup.DATA] = it }) {
                DataManagementButtons(profile != null, busy, model::clearTraining, { model.clearChats { onChatCleared() } },
                    { model.deleteAccount { AvatarImages.clear(); onChatCleared() } })
                NetworkDiagnosticsControls(busy, model::exportNetworkLogs, model::clearNetworkLogs)
                PersonalDataDeleteButton(enabled = !busy)
            }

            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelWordmark(height = 22.dp)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("版本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(BuildConfig.VERSION_NAME, style = pixelNumber(15.sp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("合并备份数据？") },
        text = { Text("相同记录不会重复导入；同 ID 的不同记录会取消整批导入。收藏、归档和偏好采用备份值。") },
        confirmButton = { TextButton(onClick = { confirmImport = false; importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("选择备份") } },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("取消") } })
}

private fun Pair<SettingsRow, Int>?.forRow(row: SettingsRow): Int = if (this?.first == row) second else 0
private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/** “已保存” stamp that presses in when a change has been written, then fades. */
@Composable
private fun SavedStamp(count: Int, modifier: Modifier = Modifier) {
    val show = remember { Animatable(0f) }
    LaunchedEffect(count) {
        if (count == 0) return@LaunchedEffect
        show.snapTo(1.4f); show.animateTo(1f, tween(120, easing = StepEasing(2))); delay(1200); show.animateTo(0f, tween(200, easing = StepEasing(3)))
    }
    if (show.value > 0f) Row(modifier.testTag("settings-saved").graphicsLayer { alpha = show.value.coerceAtMost(1f); scaleX = show.value.coerceAtLeast(1f); scaleY = scaleX },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LineIcon(Symbol.CHECK, Modifier.size(12.dp), MaterialTheme.colorScheme.primary)
        Text("已保存", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun JumpStrip(onJump: (SettingsGroup) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsGroup.entries.forEach { group ->
            val tint = if (group.danger) scheme.error else scheme.onSurfaceVariant
            Surface(onClick = { onJump(group) }, shape = RoundedCornerShape(8.dp), color = scheme.surface, border = BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier.heightIn(min = 32.dp).testTag("settings-jump-${group.name.lowercase()}")) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LineIcon(group.symbol, Modifier.size(12.dp), tint)
                    Text(group.title, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** What the settings will do at the next workout: three pixel figures, the kit, the split and the theme. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(value: UserPreferences, onJump: (SettingsRow) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val groups = MuscleGroup.entries.map { it.color() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surface).border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
        .testTag("settings-profile")) {
        Row(Modifier.fillMaxWidth().height(6.dp)) { groups.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) } }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("我的训练配置", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileFigure("${value.workoutMinutes}", "分钟", "每次训练", Modifier.weight(1f)) { onJump(SettingsRow.MINUTES) }
                ProfileFigure(if (value.restEnabled) "${value.restSeconds}" else "关", if (value.restEnabled) "秒" else "", "组间休息", Modifier.weight(1f)) { onJump(SettingsRow.REST) }
                ProfileFigure(WeightUnits.format(value.weightStepGrams, value.weightUnit), value.weightUnit.label, "重量步长", Modifier.weight(1f)) { onJump(SettingsRow.STEP) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    EquipmentOrder.forEach { type ->
                        Box(Modifier.size(24.dp).alpha(if (type in value.equipment) 1f else .28f).background(scheme.surfaceVariant, PixelShapeSmall)
                            .semantics { contentDescription = type.label() + if (type in value.equipment) "：常用" else "" }, contentAlignment = Alignment.Center) {
                            LineIcon(type.symbol(), Modifier.size(14.dp), scheme.onSurface)
                        }
                    }
                }
                // The two tags wrap under each other rather than clipping when the row runs short.
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProfileTag(value.split.label()); ProfileTag(value.theme.label())
                }
            }
        }
    }
}

@Composable
private fun ProfileFigure(value: String, unit: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value$unit，前往设置" },
        shape = PixelShapeSmall, color = scheme.surfaceVariant) {
        Column(Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = pixelNumber(32.sp), color = scheme.onSurface, maxLines = 1, softWrap = false)
                Text(unit, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun ProfileTag(text: String) {
    Text(text, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, PixelShapeSmall).padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
}

/** Group heading with a pixel icon tile, then one rounded card holding the group's rows (dashed and error-tinted for data removal). */
@Composable
private fun SettingsGroupBlock(group: SettingsGroup, onTop: (Int) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (group.danger) scheme.error else scheme.primary
    Column(Modifier.onGloballyPositioned { onTop(it.positionInParent().y.toInt()) }.testTag("settings-group-${group.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(26.dp).background(if (group.danger) scheme.errorContainer else scheme.primaryContainer, PixelShapeSmall), contentAlignment = Alignment.Center) {
                LineIcon(group.symbol, Modifier.size(15.dp), accent)
            }
            Text(group.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold), maxLines = 1)
            group.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1) }
        }
        val shape = RoundedCornerShape(16.dp)
        val card = if (group.danger) {
            val edge = lerp(scheme.error, scheme.outlineVariant, .45f)
            Modifier.clip(shape).background(lerp(scheme.errorContainer, scheme.surface, .55f)).drawBehind {
                drawRoundRect(edge, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            }
        } else Modifier.clip(shape).background(scheme.surface).border(1.dp, scheme.outlineVariant, shape)
        Column(Modifier.fillMaxWidth().then(card), content = content)
    }
}

/** A setting: label (and note) with an optional control on the right, then any wider control below. Flashes when jumped to. */
@Composable
internal fun SettingRow(title: String, note: String? = null, flash: Int = 0, trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null) {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(flash) { if (flash > 0) { glow.snapTo(1f); glow.animateTo(0f, tween(800, easing = StepEasing(4))) } }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    Column(Modifier.fillMaxWidth().drawBehind { if (glow.value > 0f) drawRect(highlight.copy(alpha = glow.value)) }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.invoke()
        }
        content?.invoke(this)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun EquipmentTiles(selected: Set<EquipmentType>, onToggle: (EquipmentType) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = fitHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        EquipmentOrder.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { type ->
                    val on = type in selected
                    Surface(onClick = { haptics.tick(); onToggle(type) }, modifier = Modifier.weight(1f).height(62.dp).testTag("equipment-${type.name.lowercase()}")
                        .semantics { this.selected = on }, shape = RoundedCornerShape(12.dp),
                        color = if (on) scheme.primaryContainer else scheme.surface, border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant)) {
                        Box {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                                LineIcon(type.symbol(), Modifier.size(22.dp), scheme.onSurface)
                                Text(type.label(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (on) CornerCheck(Modifier.align(Alignment.TopEnd).padding(5.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Small teal square with a check that stamps in over three frames. */
@Composable
private fun CornerCheck(modifier: Modifier) {
    val stamp = remember { Animatable(1.5f) }
    LaunchedEffect(Unit) { stamp.animateTo(1f, tween(150, easing = StepEasing(3))) }
    Box(modifier.size(15.dp).graphicsLayer { scaleX = stamp.value; scaleY = stamp.value }.background(MaterialTheme.colorScheme.primary, PixelShapeSmall),
        contentAlignment = Alignment.Center) { LineIcon(Symbol.CHECK, Modifier.size(10.dp), MaterialTheme.colorScheme.onPrimary) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MuscleChoiceChips(selected: Set<Muscle>, onToggle: (Muscle) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Muscle.entries.forEach { muscle ->
            val on = muscle in selected
            val group = muscle.group
            Surface(onClick = { onToggle(muscle) }, modifier = Modifier.heightIn(min = 32.dp).semantics { this.selected = on },
                shape = RoundedCornerShape(9.dp), color = if (on) group.tint() else scheme.surface,
                border = BorderStroke(1.dp, if (on) group.color() else scheme.outlineVariant)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(group.color()))
                    Text(muscle.label(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal),
                        color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(values: List<Int>, current: Int, unit: String, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value -> PresetChip("$value", unit, value == current) { if (value != current) onPick(value) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeightPresets(values: List<String>, unit: WeightUnit, current: Long, onPick: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { text ->
            val grams = WeightUnits.parse(text, unit)
            PresetChip(text, unit.label, grams == current) { if (grams != current) onPick(grams) }
        }
    }
}

@Composable
private fun PresetChip(value: String, unit: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = Modifier.height(28.dp).semantics { selected = on }, shape = PixelShapeSmall,
        color = if (on) scheme.primaryContainer else scheme.surface, border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = pixelNumber(15.sp), color = if (on) scheme.primary else scheme.onSurface)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Example sky per theme; the time theme shows its four periods and marks the current one. */
private val PeriodSwatches = listOf(Color(0xFFF6CDB0), Color(0xFFBFDDF3), Color(0xFFF2AE86), Color(0xFF2A3550))

@Composable
private fun ThemeCards(selected: ThemeMode, onPick: (ThemeMode) -> Unit) {
    val period = DayPeriod.at(LocalTime.now())
    val periodName = when (period) { DayPeriod.DAWN -> "清晨"; DayPeriod.DAY -> "白天"; DayPeriod.DUSK -> "傍晚"; DayPeriod.NIGHT -> "夜晚" }
    val cards = listOf(
        Triple(ThemeMode.TIME, "现在是$periodName", PeriodSwatches),
        Triple(ThemeMode.SYSTEM, null, listOf(Color(0xFFEEF1F6), Color(0xFF1A2029))),
        Triple(ThemeMode.LIGHT, null, listOf(Color(0xFFF5F7FA), Color(0xFFDCF1EE))),
        Triple(ThemeMode.DARK, null, listOf(Color(0xFF12161D), Color(0xFF242B36))),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (mode, note, sky) ->
                    ThemeCard(mode, note, sky, if (mode == ThemeMode.TIME) period.ordinal else null, mode == selected, Modifier.weight(1f).fillMaxHeight()) { onPick(mode) }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(mode: ThemeMode, note: String?, sky: List<Color>, mark: Int?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier.testTag("theme-${mode.name.lowercase()}").semantics { selected = on }, shape = RoundedCornerShape(12.dp),
        color = scheme.surface, border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant)) {
        Box {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Canvas(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(6.dp))) {
                    val w = size.width / sky.size
                    sky.forEachIndexed { i, c -> drawRect(c, Offset(i * w, 0f), Size(w + 1, size.height)) }
                    mark?.let {
                        val cell = 6.dp.toPx(); val x = w * (it + .5f) - cell / 2; val y = size.height - cell - 3.dp.toPx()
                        drawRect(Color(0xFF1A2332), Offset(x - 1.5.dp.toPx(), y - 1.5.dp.toPx()), Size(cell + 3.dp.toPx(), cell + 3.dp.toPx()))
                        drawRect(Color.White, Offset(x, y), Size(cell, cell))
                    }
                }
                Text(mode.label(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1) }
            }
            if (on) CornerCheck(Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
    }
}

internal enum class RowTone { PLAIN, HERO, DANGER }

/** Icon tile, title and an optional one-line note, chevron on the right: backup and data-removal entries. */
@Composable
internal fun ListRow(symbol: Symbol, title: String, note: String? = null, tone: RowTone = RowTone.PLAIN, enabled: Boolean = true, chevron: Boolean = true,
    modifier: Modifier = Modifier, figure: String? = null, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val tile = when (tone) { RowTone.HERO -> scheme.primary; RowTone.DANGER -> scheme.errorContainer; RowTone.PLAIN -> scheme.surfaceVariant }
    val glyph = when (tone) { RowTone.HERO -> scheme.onPrimary; RowTone.DANGER -> scheme.error; RowTone.PLAIN -> scheme.onSurface }
    val ink = when (tone) { RowTone.HERO -> scheme.primary; RowTone.DANGER -> scheme.error; RowTone.PLAIN -> scheme.onSurface }
    val body: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).alpha(if (enabled) 1f else .45f).background(tile, PixelShapeSmall), contentAlignment = Alignment.Center) {
                LineIcon(symbol, Modifier.size(17.dp), glyph)
            }
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.alpha(if (enabled) 1f else .45f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = ink, maxLines = 1)
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
            }
            figure?.let { Text(it, style = pixelNumber(19.sp), color = scheme.onSurfaceVariant, maxLines = 1) }
            if (chevron && onClick != null) LineIcon(Symbol.ARROW, Modifier.size(12.dp).alpha(if (enabled) 1f else .45f), scheme.onSurfaceVariant)
        }
    }
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = modifier.fillMaxWidth()) { body() }
    else Box(modifier.fillMaxWidth()) { body() }
}
