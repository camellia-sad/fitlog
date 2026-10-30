package dev.lzk.fitlog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
fun FitLogApp(container: AppContainer, openWorkoutSignal: Int = 0, onWorkoutStarted: () -> Unit = {}) {
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
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionChanged by rememberSaveable { mutableStateOf(false) }
    val todayPlanId = plan?.templateOn(LocalDate.now())
    LaunchedEffect(todayPlanId) { if (!selectionChanged) selectedTemplateId = todayPlanId }
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
    LaunchedEffect(state.error) { state.error?.let { snackbars.showSnackbar(it); model.clearError() } }
    LaunchedEffect(state.summary?.session?.id) { if (state.summary != null) nav.popBackStack("home", false) }
    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (!state.preferences.onboardingCompleted) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            OnboardingScreen(state.preferences, settingsMessage) { settings.save(it) }
        }
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            if (route in listOf("home", "library", "friends", "me") && !(route in listOf("friends","me") && socialSubpage)) NavigationBar(
                modifier = Modifier.testTag("main-navigation"), containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                val destinations = listOf("home" to "主页", "library" to "动作", "friends" to "好友", "me" to "我的")
                destinations.forEachIndexed { index, (destination, label) ->
                    NavigationBarItem(selected = route == destination,
                        onClick = {
                            if (route != destination) {
                                nav.navigate(destination) { popUpTo("home"); launchSingleTop = true }
                            }
                        },
                        icon = { LineIcon(listOf(Symbol.HOME, Symbol.DUMBBELL, Symbol.FRIENDS, Symbol.PERSON)[index], Modifier.size(23.dp),
                            if (route == destination) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                        label = { Text(label, maxLines = 1) })
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            composable("home") {
                HomeScreen(state, onStart = { onWorkoutStarted(); model.startWorkout(selectedTemplateId); goWorkout() },
                    onContinue = { onWorkoutStarted(); model.setPaused(false); goWorkout() },
                    onLibrary = { nav.navigate("library") }, onHistory = { nav.navigate("history") }, weekly = weekly,
                    templates = templateState.templates, plan = plan,
                    selectedTemplateId = selectedTemplateId,
                    onSelectTemplate = { selectedTemplateId = it; selectionChanged = true },
                    prepared = state.preparedExerciseIds.mapNotNull { id -> templateState.exercises.find { it.id == id } },
                    onRemovePrepared = model::removePreparedExercise)
            }
            composable("library") {
                ExerciseLibraryScreen(state, model, onTemplates = { nav.navigate("templates") }, onAdd = { exercise -> model.startAndAddExercise(exercise.id)
                    if (state.workout == null) {
                        selectedTemplateId = null; selectionChanged = true
                        nav.popBackStack("home", false)
                    } else goWorkout() })
            }
            composable("workout") {
                LaunchedEffect(state.selectedExercise?.id) { container.social.selectTrainingExercise(state.selectedExercise?.id) }
                WorkoutScreen(state, model, onBack = { model.setPaused(true); nav.popBackStack("home", false) }, onAdd = { nav.navigate("library") }, onFinish = { finishing = true })
            }
            composable("templates") { TemplateScreen(templates, state.workout == null, onBack = { nav.popBackStack() }, onExitEditor = { requestTemplateExit("template-list", false) }) { selectedTemplateId = it; selectionChanged = true; nav.popBackStack("home", false) } }
            composable("history") {
                LaunchedEffect(Unit) { history.selectDate(LocalDate.now()) }
                HistoryScreen(history, state.preferences.weightUnit, templateState.exercises, onBack = { nav.popBackStack() },onShare={sharingWorkout=it})
            }
            composable("friends") {
                FriendsScreen(friendsModel,container.social,onLogin={nav.navigate("me")}) {socialSubpage=it}
            }
            composable("me") {
                MeScreen(friendsModel,container.social,syncHistory={container.sharing.syncHistory()},
                    onSettings={nav.navigate("settings")}) {socialSubpage=it}
            }
            composable("settings") { SettingsScreen(settings,onBack={nav.popBackStack()}) }
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
                    Text("本次完成 ${workout.completedExerciseCount} 个动作、${workout.completedSetCount} 组。未完成草稿会保留，不计入完成数量。")
                    OutlinedTextField(feedback, { feedback = it.take(2000) }, label = { Text("今天感觉如何（选填）") }, maxLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { finishing = false; model.finishWorkout(workout.session.id, feedback) }) { Text("结束训练") } },
            dismissButton = { TextButton(onClick = { finishing = false }) { Text("继续训练") } })
    }
    state.removalRequest?.let { request ->
        AlertDialog(onDismissRequest = model::cancelSetRemoval, title = { Text("移除已完成的组？") },
            text = { Text("调整到 ${request.count} 组将删除 ${request.completedSetIds.size} 个已完成组的记录。确认后才会移除。") },
            confirmButton = { TextButton(onClick = { model.confirmSetRemoval() }) { Text("确认移除") } },
            dismissButton = { TextButton(onClick = model::cancelSetRemoval) { Text("取消") } })
    }
    state.summary?.let { summary ->
        var feeling by rememberSaveable(summary.session.id) { mutableStateOf(summary.session.feedback.orEmpty()) }
        AlertDialog(onDismissRequest = model::dismissSummary, icon = { PixelPuppy(Modifier.size(64.dp), if (summary.allSetsCompleted) PuppyPose.CELEBRATE else PuppyPose.REST) },
            title = { Text(if (summary.allSetsCompleted) "全部目标已完成" else "本次训练已结束") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkoutDetails(summary, state.preferences.weightUnit)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Text("训练感想",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                        TextButton(onClick={
                            model.saveSummaryFeedback(summary.session.id,feeling,dismiss=false) {
                                sharingWorkout=summary.copy(session=summary.session.copy(feedback=feeling))
                            }
                        }){Text("分享到动态")}
                    }
                    OutlinedTextField(feeling, { feeling = it.take(2000) }, label = { Text("训练感想（选填）") }, maxLines = 2)
                }
            },
            confirmButton = { TextButton(onClick = { model.saveSummaryFeedback(summary.session.id, feeling) }) { Text("完成") } })
    }
    sharingWorkout?.let {workout->
        WorkoutShareDialog(workout,container.friends,container.social,onClose={sharingWorkout=null},
            onLogin={sharingWorkout=null;model.dismissSummary();nav.navigate("me"){popUpTo("home");launchSingleTop=true}},
            onPublished={
                sharingWorkout=null;model.dismissSummary()
                shareScope.launch {snackbars.showSnackbar("已分享到动态")}
            })
    }
}
