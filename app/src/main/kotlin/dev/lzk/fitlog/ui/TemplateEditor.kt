package dev.lzk.fitlog.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lzk.fitlog.domain.*
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Header for full-page editors: an outlined pixel “取消” on the left, the title with an unsaved-changes note in the
 * middle, and a hard-shadow “保存” key carrying a pin on the right.
 */
@Composable
internal fun EditorHeader(title: String, saveLabel: String, dirty: Boolean, saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelOutlineKey(onClick = onCancel, enabled = !saving, minHeight = 38.dp, contentPadding = PaddingValues(start = 10.dp, end = 12.dp),
            modifier = Modifier.testTag("editor-cancel")) {
            LineIcon(Symbol.CLOSE, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant); Text("取消", maxLines = 1)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            OneLineText(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
            if (dirty) Text("有未保存的修改", style = MaterialTheme.typography.labelSmall, color = fitColors.orangeDeep, maxLines = 1)
        }
        PixelKey(onClick = onSave, enabled = !saving, minHeight = 38.dp, depth = 4.dp, contentPadding = PaddingValues(start = 11.dp, end = 14.dp),
            modifier = Modifier.testTag("editor-save")) {
            LineIcon(Symbol.PIN, Modifier.size(14.dp), LocalContentColor.current); Text(if (saving) "保存中…" else saveLabel, maxLines = 1)
        }
    }
}

private fun String.takeCodePoints(count: Int): String =
    if (codePointCount(0, length) <= count) this else substring(0, offsetByCodePoints(0, count))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateEditor(draft: TemplateDraft, templates: List<WorkoutTemplate>, exercises: List<Exercise>, preferences: UserPreferences,
    dirty: Boolean, saving: Boolean, message: String?, onBack: () -> Unit, onChange: (TemplateDraft) -> Unit, onSave: (TemplateDraft) -> Unit) {
    val latest by rememberUpdatedState(draft)
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val haptics = fitHaptics()
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.name)) }
    var selecting by remember { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Short name: filled in from the name once it is done, editable, and left alone after the user changes it.
    val others = remember(templates, draft.id) { templates.filter { it.id != draft.id } }
    val taken = remember(others) { others.shortNames() }
    val labels = remember(others) { others.map { it.label() } }
    fun suggest(value: String) = TemplateShortNames.suggest(value, labels, taken.values.toSet())
    var committed by rememberSaveable { mutableStateOf(draft.name) }
    var short by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.shortName ?: suggest(draft.name))) }
    var filled by remember { mutableIntStateOf(0) }
    val custom = short.text != suggest(committed)
    fun commitName() {
        if (committed == name.text) return
        val followed = !custom
        committed = name.text
        if (followed) {
            val value = suggest(name.text)
            if (value != short.text) { short = TextFieldValue(value, TextRange(value.length)); filled++ }
            if (latest.shortName != null) onChange(latest.copy(name = name.text, shortName = value))
        }
    }
    val nameClash = others.find { name.text.isNotBlank() && it.name.trim() == name.text.trim() }
    val shortClash = others.find { taken[it.id] == short.text.trim() }
    val shortError = when { short.text.isBlank() -> "简称不能为空"; shortClash != null -> "与“${shortClash.name}”的简称重复"; else -> null }
    val mix = remember(draft.exercises, exercises) { draft.exercises.composition(exercises) }

    fun remove(item: TemplateExercise) {
        val index = latest.exercises.indexOfFirst { it.id == item.id }
        if (index < 0) return
        if (openId == item.id) openId = null
        onChange(latest.copy(exercises = latest.exercises.filterNot { it.id == item.id }))
        val label = exercises.find { it.id == item.exerciseId }?.nameZh ?: "动作"
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar("已移除“$label”", actionLabel = "撤销", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                val list = latest.exercises.toMutableList(); list.add(index.coerceAtMost(list.size), item)
                onChange(latest.copy(exercises = list))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        EditorHeader("编辑模板", "保存模板", dirty, saving, onCancel = onBack, onSave = {
            // Saving also finishes the name, which may fill the short name in, so check against the values after that.
            commitName()
            val shortValue = short.text.trim()
            val value = latest.copy(name = name.text, shortName = shortValue)
            try {
                others.find { it.name.trim() == name.text.trim() }?.let { throw IllegalArgumentException("已有同名模板“${it.name}”") }
                if (name.text.isNotBlank() && shortValue.isEmpty()) throw IllegalArgumentException("简称不能为空")
                others.find { taken[it.id] == shortValue }?.let { throw IllegalArgumentException("与“${it.name}”的简称重复") }
                value.copy(shortName = shortValue.ifEmpty { null }).validate(); error = null; onSave(value)
            } catch (e: Exception) { error = e.message }
        })
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("template-editor"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "fields") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(name, { name = it; onChange(latest.copy(name = it.text)) }, label = { Text("模板名称") },
                            singleLine = true, enabled = !saving, isError = nameClash != null,
                            supportingText = nameClash?.let { { Text("已有同名模板“${it.name}”") } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commitName(); focus.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitName() })
                        ShortNameField(short, shortError, custom, filled, saving, mix, onValue = { value ->
                            val text = value.text.takeCodePoints(TemplateShortNames.MAX_LENGTH)
                            short = value.copy(text = text, selection = TextRange(value.selection.start.coerceAtMost(text.length)))
                            onChange(latest.copy(shortName = text))
                        }, onRestore = {
                            val value = suggest(name.text); committed = name.text
                            short = TextFieldValue(value, TextRange(value.length)); filled++
                            onChange(latest.copy(shortName = value))
                        })
                        ChoiceChips(SplitType.entries, { it == draft.splitType }, { it.label() }) { onChange(latest.copy(splitType = it)) }
                        (error ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                items(draft.exercises, key = { it.id }) { item ->
                    val index = draft.exercises.indexOf(item)
                    val exercise = exercises.find { it.id == item.exerciseId }
                    EditorCard(index, draft.exercises.size, item, exercise, preferences, open = openId == item.id,
                        modifier = Modifier.animateItem(fadeInSpec = tween(180, easing = StepEasing(3)), placementSpec = tween(180, easing = StepEasing(3)),
                            fadeOutSpec = tween(150, easing = StepEasing(3))),
                        onToggle = { haptics.tick(); openId = if (openId == item.id) null else item.id },
                        onMove = { by -> haptics.tick(); onChange(latest.copy(exercises = latest.exercises.moved(index, index + by))) },
                        onRemove = { remove(item) },
                        onChange = { updated -> onChange(latest.copy(exercises = latest.exercises.map { if (it.id == updated.id) updated else it })) })
                }
                item(key = "add") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(onClick = { selecting = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
                            val outline = MaterialTheme.colorScheme.outline
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).drawBehind {
                                drawRoundRect(outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
                            }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                LineIcon(Symbol.PLUS, Modifier.size(13.dp)); Text("添加模板动作", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text("只修改模板目标，不会改动已开始的训练。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp))
        }
    }
    if (selecting) ExercisePickerSheet(exercises, onDismiss = { selecting = false }) { exercise ->
        onChange(latest.copy(exercises = latest.exercises + TemplateExercise(UUID.randomUUID().toString(), exercise.id, latest.exercises.size,
            targetRepMin = exercise.recordMetric.defaultMin, targetRepMax = exercise.recordMetric.defaultMax)))
        selecting = false
    }
}

/** Same field component as the name, with a live preview of the timetable cell beside it. */
@Composable
private fun ShortNameField(value: TextFieldValue, error: String?, custom: Boolean, filled: Int, saving: Boolean, mix: List<GroupShare>,
    onValue: (TextFieldValue) -> Unit, onRestore: () -> Unit) {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(filled) { if (filled > 0) { flash.snapTo(1f); flash.animateTo(0f, tween(600, easing = StepEasing(4))) } }
    val glow = MaterialTheme.colorScheme.primaryContainer
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        OutlinedTextField(value, onValue, label = { Text("简称 · 最多 ${TemplateShortNames.MAX_LENGTH} 个字") }, singleLine = true, enabled = !saving,
            isError = error != null,
            supportingText = {
                when {
                    error != null -> Text(error)
                    custom -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已自定义 · ")
                        Text("恢复自动", Modifier.clickable(onClick = onRestore).testTag("short-name-restore"),
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    else -> Text("按名称自动生成，可修改", maxLines = 1)
                }
            },
            modifier = Modifier.weight(1f).testTag("template-short-name")
                .drawBehind { if (flash.value > 0f) drawRoundRect(glow.copy(alpha = flash.value), topLeft = Offset(0f, 8.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width, 56.dp.toPx().coerceAtMost(size.height - 8.dp.toPx())),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())) })
        ShortNamePreview(value.text, mix, Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExercisePickerSheet(exercises: List<Exercise>, onDismiss: () -> Unit, onPick: (Exercise) -> Unit) {
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val matches = remember(exercises, search.text) {
        val query = search.text.trim()
        exercises.filter { !it.isArchived && (it.nameZh.contains(query, ignoreCase = true) || it.nameEn.contains(query, ignoreCase = true)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OneLineText("添加模板动作", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().testTag("template-exercise-search"),
                singleLine = true, placeholder = { Text("搜索动作中文名或英文名") },
                leadingIcon = { LineIcon(Symbol.SEARCH, Modifier.size(22.dp)) },
                trailingIcon = {
                    if (search.text.isNotEmpty()) IconButton(onClick = { search = TextFieldValue("") },
                        modifier = Modifier.semanticsLabel("清空模板动作搜索")) { LineIcon(Symbol.CLOSE, Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant) }
                })
            Text("${matches.size} 个动作", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f, fill = false).testTag("template-exercise-picker"), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (matches.isEmpty()) item { Text("没有符合名称的动作，试试其他关键词。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(matches, key = { it.id }) { exercise ->
                    TextButton(onClick = { onPick(exercise) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(exercise.nameZh, maxLines = 1) }
                }
            }
        }
    }
}

/* ---------------- exercise card ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorCard(index: Int, count: Int, item: TemplateExercise, exercise: Exercise?, preferences: UserPreferences, open: Boolean,
    modifier: Modifier, onToggle: () -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit, onChange: (TemplateExercise) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val group = exercise?.primaryMuscles?.firstOrNull()?.group
    val hue = group?.color() ?: scheme.outline
    val tint = group?.tint() ?: scheme.surfaceVariant
    val metric = exercise?.recordMetric ?: RecordMetric.REPETITIONS
    val edge = if (open) lerp(hue, scheme.outline, .3f) else scheme.outlineVariant
    Surface(modifier.fillMaxWidth().testTag("template-item-$index"), shape = RoundedCornerShape(16.dp), color = scheme.surface,
        border = BorderStroke(if (open) 1.5.dp else 1.dp, edge)) {
        Column {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 10.dp, bottom = 10.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val divider = scheme.outlineVariant
                Box(Modifier.width(26.dp).fillMaxHeight().drawBehind {
                    drawLine(divider, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                }, contentAlignment = Alignment.Center) {
                    Text(String.format(java.util.Locale.ROOT, "%02d", index + 1), style = pixelNumber(18.sp), color = scheme.onSurfaceVariant, maxLines = 1)
                }
                ExerciseThumb(item.exerciseId, exercise?.nameZh ?: "已归档动作", group, exercise?.equipmentType,
                    Modifier.size(52.dp).align(Alignment.CenterVertically), corner = 10.dp)
                Column(Modifier.weight(1f).align(Alignment.CenterVertically).clickable(onClickLabel = "调整目标", onClick = onToggle),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(exercise?.nameZh ?: "已归档动作", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TargetChips(item, metric, preferences.weightUnit, hue, tint)
                }
                Column(Modifier.align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniKey(Symbol.UP, "上移", enabled = index > 0) { onMove(-1) }
                    MiniKey(Symbol.DOWN, "下移", enabled = index < count - 1) { onMove(1) }
                }
                Column(Modifier.align(Alignment.CenterVertically), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniKey(Symbol.TUNE, "调整目标", active = open, tint = scheme.primary, onClick = onToggle)
                    MiniKey(Symbol.CLOSE, "移除", onClick = onRemove)
                }
            }
            AnimatedVisibility(open, enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(140))) {
                TargetAdjuster(item, exercise, metric, preferences, hue, tint, onChange, onDone = onToggle)
            }
        }
    }
}

@Composable
private fun MiniKey(symbol: Symbol, label: String, enabled: Boolean = true, active: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp).semantics { contentDescription = label; if (active) selected = true },
        shape = RoundedCornerShape(8.dp), color = if (active) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(1.dp, if (active) scheme.primary else scheme.outlineVariant)) {
        Box(contentAlignment = Alignment.Center) { LineIcon(symbol, Modifier.size(13.dp).alpha(if (enabled) 1f else .35f), tint) }
    }
}

/** The targets as small pixel-cut tags; the one that just changed flashes in the group colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetChips(item: TemplateExercise, metric: RecordMetric, unit: WeightUnit, hue: Color, tint: Color) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TargetChip(null, "${item.targetSetCount}", "组", item.targetSetCount, hue)
        TargetChip(null, "${item.targetRepMin}–${item.targetRepMax}", metric.unit, item.targetRepMin to item.targetRepMax, hue)
        if (metric == RecordMetric.REPETITIONS) {
            if (item.useExerciseWeight) TargetChip("跟随重量", null, null, true, hue)
            else TargetChip(null, WeightUnits.format(item.defaultWeightGrams, unit), unit.label, item.defaultWeightGrams, hue)
        }
        TargetChip("休息", "${item.restSeconds}", "秒", item.restSeconds, hue)
    }
}

@Composable
private fun TargetChip(prefix: String?, value: String?, suffix: String?, key: Any, hue: Color) {
    val scheme = MaterialTheme.colorScheme
    val flash = remember { Animatable(0f) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(key) { if (!seen) { seen = true; return@LaunchedEffect }; flash.snapTo(1f); flash.animateTo(0f, tween(500, easing = StepEasing(3))) }
    val label = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    Row(Modifier.height(22.dp).background(lerp(scheme.surfaceVariant, lerp(hue, scheme.surface, .5f), flash.value), PixelShapeSmall).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        prefix?.let { Text(it, style = label, color = scheme.onSurfaceVariant, maxLines = 1) }
        value?.let { Text(it, style = pixelNumber(15.sp), color = scheme.onSurface, maxLines = 1) }
        suffix?.let { Text(it, style = label, color = scheme.onSurfaceVariant, maxLines = 1) }
    }
}

/* ---------------- target adjuster ---------------- */

private val RestPresets = listOf(0, 60, 90, 120, 180)
private const val MAX_WEIGHT_GRAMS = 999_000L

/**
 * Targets for one exercise, opened inside its card: sets with one pixel per set, a rep (or seconds) range over a
 * pixel ruler, the weight mode, and rest with presets. Every change is written to the draft straight away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetAdjuster(item: TemplateExercise, exercise: Exercise?, metric: RecordMetric, preferences: UserPreferences, hue: Color, tint: Color,
    onChange: (TemplateExercise) -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val unit = preferences.weightUnit
    val seconds = metric == RecordMetric.SECONDS
    var exact by remember { mutableStateOf(false) }
    val line = lerp(hue, scheme.outlineVariant, .55f)
    Column(Modifier.fillMaxWidth().background(lerp(tint, scheme.surface, .45f)).drawBehind {
        drawLine(line, Offset.Zero, Offset(size.width, 0f), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
    }.padding(12.dp).testTag("target-adjuster"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section("组数", trailing = {
            Stepper(item.targetSetCount.toLong(), 1, WorkoutRules.MAX_SET_COUNT.toLong(), 1, "组数", tag = "target-sets") {
                onChange(item.copy(targetSetCount = it.toInt()))
            }
        }) { SetPips(item.targetSetCount, hue) }

        val step = if (seconds) 5L else 1L
        val top = if (seconds) 3600L else 100L
        Section(if (seconds) "目标秒数" else "目标次数") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Stepper(item.targetRepMin.toLong(), step, top, step, "最少${metric.unit}数", tag = "target-min") { v ->
                    onChange(item.copy(targetRepMin = v.toInt(), targetRepMax = maxOf(item.targetRepMax, v.toInt())))
                }
                Text("–", style = pixelNumber(22.sp), color = scheme.onSurfaceVariant)
                Stepper(item.targetRepMax.toLong(), step, top, step, "最多${metric.unit}数", unit = metric.unit, tag = "target-max") { v ->
                    onChange(item.copy(targetRepMax = v.toInt(), targetRepMin = minOf(item.targetRepMin, v.toInt())))
                }
            }
            RangeRuler(item.targetRepMin, item.targetRepMax, if (seconds) 6 else 1, hue)
        }

        if (!seconds) Section("重量") {
            WeightMode(item.useExerciseWeight) { follow ->
                onChange(if (follow) item.copy(useExerciseWeight = true, defaultWeightGrams = 0)
                    else item.copy(useExerciseWeight = false, defaultWeightGrams = item.defaultWeightGrams.takeIf { it > 0 } ?: exercise?.defaultWeightGrams ?: 0))
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                if (item.useExerciseWeight) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("开始训练时读取动作重量，当前", Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    exercise?.defaultWeightGrams?.let {
                        Text(WeightUnits.format(it, unit), style = pixelNumber(18.sp), color = scheme.onSurface)
                        Text(unit.label, Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } ?: Text("尚未记录", Modifier.padding(bottom = 1.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
                } else Stepper(item.defaultWeightGrams, 0, MAX_WEIGHT_GRAMS, preferences.weightStepGrams, "重量", valueWidth = 64.dp,
                    format = { WeightUnits.format(it, unit) }, unit = unit.label, tag = "target-weight", onValueClick = { exact = true }) {
                    onChange(item.copy(defaultWeightGrams = it))
                }
            }
        }

        Section("组间休息", trailing = {
            Stepper(item.restSeconds.toLong(), 0, 3600, 15, "休息秒数", valueWidth = 62.dp, unit = "秒", tag = "target-rest") { onChange(item.copy(restSeconds = it.toInt())) }
        }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                RestPresets.forEach { value ->
                    val on = item.restSeconds == value
                    Surface(onClick = { if (!on) onChange(item.copy(restSeconds = value)) }, modifier = Modifier.height(28.dp).testTag("rest-preset-$value"),
                        shape = PixelShapeSmall, color = if (on) scheme.primaryContainer else scheme.surface,
                        border = BorderStroke(if (on) 1.5.dp else 1.dp, if (on) scheme.primary else scheme.outlineVariant)) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (value == 0) Text("不休息", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                            else {
                                Text("$value", style = pixelNumber(15.sp), color = if (on) scheme.primary else scheme.onSurface)
                                Text("秒", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("改动会立即写入草稿", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            PixelKey(onClick = onDone, minHeight = 34.dp, contentPadding = PaddingValues(start = 10.dp, end = 12.dp), modifier = Modifier.testTag("target-done")) {
                LineIcon(Symbol.CHECK, Modifier.size(12.dp), LocalContentColor.current); Text("完成", maxLines = 1)
            }
        }
    }
    if (exact) ExactWeightDialog(item.defaultWeightGrams, unit, onDismiss = { exact = false }) { onChange(item.copy(defaultWeightGrams = it)); exact = false }
}

@Composable
private fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            trailing?.invoke()
        }
        content()
    }
}

/** − value + : pixel keys that sink when pressed and repeat when held; the value rolls over three frames. */
@Composable
private fun Stepper(value: Long, min: Long, max: Long, step: Long, label: String, valueWidth: Dp = 48.dp, format: (Long) -> String = { it.toString() },
    unit: String? = null, tag: String, onValueClick: (() -> Unit)? = null, onChange: (Long) -> Unit) {
    val haptics = fitHaptics()
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    fun bump(by: Long) {
        val next = (current + by).coerceIn(min, max)
        if (next != current) { haptics.tick(); change(next) }
    }
    val ink = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        PixelOutlineKey(onClick = { bump(-step) }, enabled = value > min, minHeight = 32.dp, minWidth = 32.dp, contentPadding = PaddingValues(0.dp),
            repeatOnHold = true, modifier = Modifier.semantics { contentDescription = "减少$label" }) { LineIcon(Symbol.MINUS, Modifier.size(15.dp), ink) }
        Box(Modifier.width(valueWidth).height(34.dp).padding(bottom = 3.dp).clipToBounds()
            .then(if (onValueClick != null) Modifier.clickable(onClickLabel = "输入$label", onClick = onValueClick) else Modifier)
            .testTag("$tag-value").semantics { contentDescription = "$label ${format(value)}${unit ?: ""}" }, contentAlignment = Alignment.Center) {
            AnimatedContent(value, transitionSpec = {
                val up = targetState > initialState
                slideInVertically(tween(150, easing = StepEasing(3))) { if (up) it else -it } togetherWith
                    slideOutVertically(tween(150, easing = StepEasing(3))) { if (up) -it else it }
            }, label = label) { shown ->
                Text(format(shown), style = pixelNumber(26.sp), color = ink, maxLines = 1, softWrap = false)
            }
        }
        PixelOutlineKey(onClick = { bump(step) }, enabled = value < max, minHeight = 32.dp, minWidth = 32.dp, contentPadding = PaddingValues(0.dp),
            repeatOnHold = true, modifier = Modifier.semantics { contentDescription = "增加$label" }) { LineIcon(Symbol.PLUS, Modifier.size(15.dp), ink) }
        unit?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

/** One pixel per target set; a new one pops in over three frames. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetPips(count: Int, hue: Color) {
    val shown = count.coerceAtMost(12)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        repeat(shown) { index ->
            key(index) {
                val grow = remember { Animatable(0f) }
                LaunchedEffect(Unit) { grow.animateTo(1f, tween(180, easing = StepEasing(3))) }
                Box(Modifier.size(10.dp).graphicsLayer { scaleX = grow.value; scaleY = grow.value }.background(hue))
            }
        }
        if (count > shown) Text("+${count - shown}", Modifier.padding(start = 3.dp), style = pixelNumber(14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A 20-cell pixel ruler with the target band lit; [cell] is the value each cell stands for. */
@Composable
private fun RangeRuler(min: Int, max: Int, cell: Int, hue: Color) {
    val dim = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val gap = 2.dp.toPx(); val w = (size.width - gap * 19) / 20
            for (i in 0 until 20) {
                val v = (i + 1) * cell
                drawRect(if (v in min..max) hue else dim, Offset(i * (w + gap), 0f), androidx.compose.ui.geometry.Size(w, size.height))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("$cell", "${cell * 10}", "${cell * 20}+").forEach {
                Text(it, style = pixelNumber(12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Two-way switch whose pixel slider block steps across in three frames. */
@Composable
private fun WeightMode(follow: Boolean, onSelect: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp).background(scheme.surfaceVariant, PixelShapeSmall).padding(3.dp)) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (follow) 0.dp else half, tween(150, easing = StepEasing(3)), label = "重量方式")
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(half).fillMaxHeight().background(scheme.surface, PixelShapeSmall).border(1.5.dp, scheme.primary, PixelShapeSmall))
        Row(Modifier.fillMaxSize()) {
            listOf(true to "跟随动作重量", false to "固定重量").forEach { (value, text) ->
                val on = follow == value
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (!on) onSelect(value) }
                    .semantics { role = Role.Tab; selected = on }.testTag(if (value) "weight-follow" else "weight-fixed"), contentAlignment = Alignment.Center) {
                    Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium),
                        color = if (on) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ExactWeightDialog(grams: Long, unit: WeightUnit, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var text by remember { mutableStateOf(WeightUnits.format(grams, unit, editing = true)) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { OneLineText("模板固定重量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it; error = null }, label = { Text("重量（${unit.label}）") }, singleLine = true,
                    isError = error != null, modifier = Modifier.testTag("exact-weight-input"))
                Text(error ?: "本模板优先使用此重量，包括 0。", style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = {
            try { val value = WeightUnits.parse(text, unit); WorkoutRules.draft(value, 0); onSave(value) } catch (e: Exception) { error = e.message ?: "重量格式无效" }
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { if (to in indices) add(to, removeAt(from)) }
