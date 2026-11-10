package dev.lzk.fitlog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import dev.lzk.fitlog.AppContainer
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.*
import dev.lzk.fitlog.training.WorkoutViewModel
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

@Composable
fun FitLogApp(container: AppContainer, openWorkoutSignal: Int = 0, socialOpen: String? = null, onSocialOpened: () -> Unit = {},
    onWorkoutStarted: () -> Unit = {}) {
    val model: WorkoutViewModel = viewModel(factory = WorkoutViewModel.Factory(container))
    val templates: TemplateViewModel = viewModel(factory = AppModelFactory(container))
    val settings: SettingsViewModel = viewModel(factory = AppModelFactory(container))
    val history: HistoryViewModel = viewModel(factory = AppModelFactory(container))
    val friendsModel: FriendsViewModel = viewModel(factory = FriendsViewModel.Factory(container.friends))
    val state by model.state.collectAsStateWithLifecycle()
    val templateState by templates.state.collectAsStateWithLifecycle()
    val templateEditor by templates.editing.collectAsStateWithLifecycle()
    val templateSaving by templates.saving.collectAsStateWithLifecycle()
    val templateMessage by templates.message.collectAsStateWithLifecycle()
    val plan by templates.plan.collectAsStateWithLifecycle()
    // The slot waiting on “暂停当前训练？”: a template id, or "" for the free record.
    var switchingTo by rememberSaveable { mutableStateOf<String?>(null) }
    val settingsMessage by settings.message.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val snackbars = remember { SnackbarHostState() }
    var socialSubpage by remember { mutableStateOf(false) }
    var finishing by rememberSaveable { mutableStateOf(false) }
    var sharingWorkout by remember {mutableStateOf<Workout?>(null)}
    val shareScope=rememberCoroutineScope()
    var pendingTemplateExit by rememberSaveable { mutableStateOf<String?>(null) }
    // A custom exercise made from the picker comes back selected there.
    var customForPicker by rememberSaveable { mutableStateOf(false) }
    var pickedCustom by rememberSaveable { mutableStateOf<String?>(null) }
    var resumeWorkoutAfterExit by rememberSaveable { mutableStateOf(false) }
    val monday = remember { LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val weeklyFlow = remember(container, monday) {
        container.training.observeHistory(monday, monday.plusDays(6)).catch {
            emit(emptyList())
            snackbars.showSnackbar("读取本周记录失败，请重新打开应用")
        }
    }
    val weekly by weeklyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val goWorkout = { nav.navigate("workout") { popUpTo("home"); launchSingleTop = true } }
    val openNow: (String?) -> Unit = { slot -> onWorkoutStarted(); model.openRecord(slot); goWorkout() }
    // Every way into training goes through here: a running session with completed sets is paused only after a confirm.
    val requestOpen: (String?) -> Unit = { slot -> if (state.switchingLosesFocus(slot.orEmpty())) switchingTo = slot.orEmpty() else openNow(slot) }
    val finishTemplateExit: (String, Boolean) -> Unit = { destination, resume ->
        if (destination == "workout") {
            goWorkout()
            if (resume) model.setPaused(false)
        } else if (destination != "template-list") {
            nav.navigate(destination) { popUpTo("home"); launchSingleTop = true }
        }
    }
    val requestTemplateExit: (String, Boolean) -> Unit = { destination, resume ->
        if (!templates.saving.value) {
            if (templates.hasUnsavedChanges) {
                templates.message.value = null
                pendingTemplateExit = destination
                resumeWorkoutAfterExit = resume
            } else {
                templates.closeEditor()
                finishTemplateExit(destination, resume)
            }
        }
    }
    LaunchedEffect(templateEditor, pendingTemplateExit) {
        val destination = pendingTemplateExit
        if (destination != null && templateEditor == null) {
            val resume = resumeWorkoutAfterExit
            pendingTemplateExit = null
            resumeWorkoutAfterExit = false
            finishTemplateExit(destination, resume)
        }
    }
    LaunchedEffect(route, state.workout?.session?.id, state.workout?.session?.status) {
        if (route == "home" && state.workout?.session?.status == SessionStatus.ACTIVE) model.setPaused(true)
    }
    LaunchedEffect(openWorkoutSignal, state.loading) {
        if (openWorkoutSignal > 0 && !state.loading && state.workout != null) {
            if (route == "templates") requestTemplateExit("workout", true)
            else { goWorkout(); model.setPaused(false) }
        }
    }
    // Each launch quietly copies one's own posts (and their images) to the device; nothing is shown either way.
    val accountId = friendsModel.profile.collectAsStateWithLifecycle().value?.id
    LaunchedEffect(accountId) { if (accountId != null) container.social.syncOwnPosts() }
    LaunchedEffect(state.error) { state.error?.let { snackbars.showSnackbar(it); model.clearError() } }
    LaunchedEffect(state.summary?.session?.id) { if (state.summary != null) nav.popBackStack("home", false) }
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (!state.preferences.onboardingCompleted) {
        Box(Modifier.fillMaxSize()) {
            FitBackdrop(BackdropKind.HORIZON)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    OnboardingScreen(state.preferences, settingsMessage) { settings.save(it) }
                }
            }
        }
        return
    }
    LaunchedEffect(route) { ThemeClock.holdForWorkout = route == "workout" }
    // A tapped friends notification leads to the friends tab, which then opens the chat or the feed.
    LaunchedEffect(socialOpen) {
        if (socialOpen != null && route != "friends") {
            if (route == "templates") requestTemplateExit("friends", false)
            else nav.navigate("friends") { popUpTo("home"); launchSingleTop = true }
        }
    }
    val addExercise: (Exercise) -> Unit = { exercise ->
        model.startAndAddExercise(exercise.id)
        if (state.workout == null) nav.popBackStack("home", false) else goWorkout()
    }
    CompositionLocalProvider(LocalHapticsEnabled provides state.preferences.hapticsEnabled) {
    Box(Modifier.fillMaxSize()) {
    FitBackdrop(when (route) { "home" -> BackdropKind.SKY; "workout" -> BackdropKind.PLAIN; else -> BackdropKind.HORIZON })
    Scaffold(
        // Transparent so the backdrop shows; contentColor must then be explicit, or text falls back to black.
        containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            if (route in listOf("home", "library", "friends", "me") && !(route in listOf("friends","me") && socialSubpage)) FitNavigationBar(route) { destination ->
                nav.navigate(destination) { popUpTo("home"); launchSingleTop = true }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            composable("home") {
                HomeScreen(state, onOpen = requestOpen,
                    onContinue = {
                        if (state.workout != null) { onWorkoutStarted(); model.setPaused(false); goWorkout() }
                        else state.todayWorkout?.let { openNow(it.session.daySlot) }
                    },
                    onLibrary = { nav.navigate("pick/today") }, onHistory = { nav.navigate("history") }, weekly = weekly,
                    templates = templateState.templates, plan = plan, updates=container.updates,
                    onAddDailySet = model::addDailySet, onRemoveAction = model::removeExercise, catalog = templateState.exercises,
                    onTemplates = { nav.navigate("templates") })
            }
            composable("backups") {BackupScreen(container,onBack={nav.popBackStack()},onLogin={nav.navigate("me")})}
            composable("library") {
                ExerciseLibraryScreen(state, model, onTemplates = { nav.navigate("templates") },
                    onOpen = { nav.navigate("exercise/" + android.net.Uri.encode(it.id)) }, onAdd = addExercise,
                    onCustom = { customForPicker = false; nav.navigate("custom-exercise") })
            }
            composable("pick/{purpose}") { entry ->
                val purpose = PickPurpose.of(entry.arguments?.getString("purpose"))
                val draft = templateEditor
                val shownWorkout = state.workout ?: state.review ?: state.todayWorkout
                val target = when (purpose) {
                    PickPurpose.TODAY -> shownWorkout?.session?.trainingLabel ?: "自由训练"
                    PickPurpose.TEMPLATE -> "模板 · " + (draft?.name?.trim()?.ifEmpty { null } ?: "新模板")
                    PickPurpose.TREND -> "动作趋势"
                }
                val added = when (purpose) {
                    PickPurpose.TODAY -> shownWorkout?.exercises?.filter { !it.removedFromPlan }?.map { it.exerciseId }?.toSet() ?: state.preparedExerciseIds.toSet()
                    PickPurpose.TEMPLATE -> draft?.exercises?.map { it.exerciseId }?.toSet().orEmpty()
                    PickPurpose.TREND -> emptySet()
                }
                ExercisePickerScreen(purpose, target, templateState.exercises, added, incoming = pickedCustom, onIncomingTaken = { pickedCustom = null },
                    onBack = { nav.popBackStack() }, onCustom = { customForPicker = true; nav.navigate("custom-exercise") }) { ids ->
                    when (purpose) {
                        PickPurpose.TODAY -> model.addExercises(ids)
                        PickPurpose.TEMPLATE -> templates.addExercises(ids.mapNotNull { id -> templateState.exercises.find { it.id == id } })
                        PickPurpose.TREND -> history.selectedExerciseId.value = ids.firstOrNull()
                    }
                    nav.popBackStack()
                }
            }
            composable("custom-exercise") {
                CustomExerciseScreen(saving = false, message = null, onCancel = { nav.popBackStack() }) { draft ->
                    model.createCustom(draft) { id -> if (customForPicker) pickedCustom = id; nav.popBackStack() }
                }
            }
            composable("exercise/{exerciseId}") { entry ->
                val id = entry.arguments?.getString("exerciseId")
                ExerciseDetailScreen(state.exercises.find { it.id == id } ?: templateState.exercises.find { it.id == id }, state, model,
                    onBack = { nav.popBackStack() },
                    onHistory = { history.selectedExerciseId.value = it; nav.navigate("exercise-history/" + android.net.Uri.encode(it)) },
                    onAdd = addExercise)
            }
            composable("workout") {
                LaunchedEffect(state.selectedExercise?.id) { container.social.selectTrainingExercise(state.selectedExercise?.id) }
                WorkoutScreen(state, model, onBack = { model.stopReviewing(); model.setPaused(true); nav.popBackStack("home", false) }, onAdd = { nav.navigate("pick/today") },
                    onFinish = { finishing = true }, catalog = templateState.exercises)
            }
            composable("templates") {
                // Postponing stays possible until today's planned template has a completed set.
                val planToday = plan?.let { if (it.isPostponed(state.todayDate)) it.undoPostpone(state.todayDate) else it }?.templateOn(state.todayDate)
                TemplateScreen(templates, true, onBack = { nav.popBackStack() }, onExitEditor = { requestTemplateExit("template-list", false) },
                    todayStarted = planToday != null && state.dayRecords.any { it.session.daySlot == planToday && it.completedSetCount > 0 },
                    onPickExercises = { nav.navigate("pick/template") }, onStart = requestOpen)
            }
            composable("history") {
                LaunchedEffect(Unit) { history.selectDate(LocalDate.now()) }
                HistoryScreen(history, state.preferences.weightUnit, templateState.exercises, onBack = { nav.popBackStack() }, onShare = { sharingWorkout = it },
                    onPickExercise = { nav.navigate("pick/trend") })
            }
            composable("exercise-history/{exerciseId}") { entry ->
                val exerciseId = entry.arguments?.getString("exerciseId")
                LaunchedEffect(exerciseId) { history.selectedExerciseId.value = exerciseId }
                val records by history.performance.collectAsStateWithLifecycle()
                ExerciseHistoryScreen(exerciseId?.let { id -> templateState.exercises.find { it.id == id } },
                    records, state.preferences.weightUnit, onBack = { nav.popBackStack() })
            }
            composable("friends") {
                FriendsScreen(friendsModel,container.social,onLogin={nav.navigate("me")},open=socialOpen,onOpened=onSocialOpened) {socialSubpage=it}
            }
            composable("me") {
                val friendList by friendsModel.friends.collectAsStateWithLifecycle()
                val today = remember { LocalDate.now() }
                val yearStart = today.withDayOfYear(1)
                // Two years back so the week streak and the 16-week grid are not cut at New Year.
                val recentFlow = remember(container, today) { container.training.observeHistory(minOf(yearStart, today.minusWeeks(104)), today).catch { emit(emptyList()) } }
                val recent by recentFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                val year = remember(recent) { recent.filter { !it.session.localWorkoutDate.isBefore(yearStart) } }
                val stats = remember(year) { TrainingHistory.statistics(year) }
                val activity = remember(recent, templateState.exercises) { profileActivity(recent, templateState.exercises, today) }
                MeScreen(friendsModel, container.social, onSettings = { nav.navigate("settings") }, onSubpage = { socialSubpage = it },
                    figures = listOf("${stats.days}" to "今年训练天", "${stats.sessions}" to "次", "${friendList.count { it.accepted }}" to "好友"),
                    activity = activity,
                    ownHistory = {
                        LaunchedEffect(Unit) { history.selectDate(LocalDate.now()) }
                        HistoryScreen(history, state.preferences.weightUnit, templateState.exercises, onBack = {}, onShare = { sharingWorkout = it },
                            onPickExercise = { nav.navigate("pick/trend") }, embedded = true)
                    })
            }
            composable("settings") {
                SettingsScreen(settings, onBackups = { nav.navigate("backups") }, onBack = { nav.popBackStack() }, onChatCleared = friendsModel::clearChatPreviews,
                    social = { SocialPrivacyRows(friendsModel, container.social, syncHistory = { container.sharing.syncHistory() }) })
            }
        }
    }
    }
    if (pendingTemplateExit != null && templateEditor != null) {
        TemplateDraftExitDialog(templateSaving, templateMessage, templates::keepDraft, templates::discardDraft) {
            pendingTemplateExit = null
            resumeWorkoutAfterExit = false
        }
    }
    if (finishing && state.workout != null) {
        val workout = requireNotNull(state.workout)
        var feedback by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { finishing = false }, title = { Text("结束这次训练？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("本次完成 ${workout.completedExerciseCount} 个动作、${workout.completedSetCount} 组。" +
                        if(workout.session.dailyPlanDate != null) (if (workout.completedSetCount == 0) "还没有完成组，结束后不保留这条记录。" else "结束只停止计时，全部进度保留在这条记录，稍后可继续。") else "未完成草稿会保留，不计入完成数量。")
                    OutlinedTextField(feedback, { feedback = it.take(2000) }, label = { Text("今天感觉如何（选填）") }, maxLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { finishing = false; model.finishWorkout(workout.session.id, feedback) }) { Text("结束训练") } },
            dismissButton = { TextButton(onClick = { finishing = false }) { Text("继续训练") } })
    }
    switchingTo?.let { slot ->
        val running = state.workout
        if (running == null) switchingTo = null
        else SwitchTrainingDialog(running, if (slot.isEmpty()) (if (state.dayRecords.any { !it.session.daySlot.isNullOrEmpty() }) "加练" else "自由训练")
            else templateState.templates.find { it.id == slot }?.name ?: "训练",
            onCancel = { switchingTo = null }) { switchingTo = null; openNow(slot) }
    }
    state.removalRequest?.let { request ->
        AlertDialog(onDismissRequest = model::cancelSetRemoval, title = { Text("移除已完成的组？") },
            text = { Text("调整到 ${request.count} 组将删除 ${request.completedSetIds.size} 个已完成组的记录。确认后才会移除。") },
            confirmButton = { TextButton(onClick = { model.confirmSetRemoval() }) { Text("确认移除") } },
            dismissButton = { TextButton(onClick = model::cancelSetRemoval) { Text("取消") } })
    }
    state.summary?.let { summary ->
        WorkoutSummaryDialog(summary, state.summaryRecords, templateState.exercises, state.preferences.weightUnit,
            onShare = { feeling ->
                model.saveSummaryFeedback(summary.session.id, feeling, dismiss = false) {
                    sharingWorkout = summary.copy(session = summary.session.copy(feedback = feeling.trim().ifBlank { null }))
                }
            }, onDone = { feeling -> model.saveSummaryFeedback(summary.session.id, feeling) })
    }
    sharingWorkout?.let {workout->
        WorkoutShareDialog(workout,container.friends,container.social,unit=state.preferences.weightUnit,onClose={sharingWorkout=null},
            onLogin={sharingWorkout=null;model.dismissSummary();nav.navigate("me"){popUpTo("home");launchSingleTop=true}},
            onPublished={
                sharingWorkout=null;model.dismissSummary()
                shareScope.launch {snackbars.showSnackbar("已分享到动态")}
            },catalog=templateState.exercises,loadRecords=model::recordsFor)
    }
    }
}

/** Bottom navigation with a pixel indicator; the newly selected icon hops 3dp in two frames. */
@Composable
private fun FitNavigationBar(route: String, onSelect: (String) -> Unit) {
    val destinations = listOf(Triple("home", "主页", Symbol.HOME), Triple("library", "动作", Symbol.DUMBBELL),
        Triple("friends", "好友", Symbol.FRIENDS), Triple("me", "我的", Symbol.PERSON))
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("main-navigation")) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(64.dp)) {
                destinations.forEach { (destination, label, symbol) ->
                    val selected = route == destination
                    val hop = remember { androidx.compose.animation.core.Animatable(0f) }
                    LaunchedEffect(selected) {
                        if (selected) { hop.snapTo(-3f); kotlinx.coroutines.delay(120); hop.snapTo(0f) }
                    }
                    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected, role = Role.Tab) { if (!selected) onSelect(destination) },
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, androidx.compose.ui.Alignment.CenterVertically),
                    ) {
                        Box(Modifier.size(56.dp, 30.dp).background(if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, PixelShapeSmall),
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            LineIcon(symbol, Modifier.size(22.dp).offset { androidx.compose.ui.unit.IntOffset(0, hop.value.dp.roundToPx()) }, tint)
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal),
                            color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}
