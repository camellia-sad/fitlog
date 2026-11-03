package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.data.RoomWorkoutRepository
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.features.HistoryViewModel
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClearTrainingDateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "clear-date-ui-" + UUID.randomUUID()
    private lateinit var container: AppContainer
    private val models = ViewModelStore()
    private lateinit var yesterday: String
    private lateinit var template: String
    @Before fun setup() = runBlocking<Unit> {
        container = AppContainer(context, name); container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true))
        template = container.training.saveTemplate(TemplateDraft(name = "日期测试模板",
            exercises = listOf(TemplateExercise("a", "barbell-bench-press", 0))))
        // Seed an earlier date through a normal repository, retaining the real app container.
        val db = dev.lzk.fitlog.data.FitLogDatabase.create(context, name)
        try {
            val oldRepo = RoomWorkoutRepository(db, Clock.fixed(LocalDate.now().minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
            val old = oldRepo.startWorkout(template)
            yesterday = old.session.id
            oldRepo.finishWorkout(yesterday, "昨日")
        } finally { db.close() }
        val daily = container.workouts.startDailyWorkout(template)
        val e = daily.exercises.single()
        container.workouts.completeSet(e.id, e.sets.first().id, 30_000, 8)
        container.workouts.finishWorkout(daily.session.id, "今日")
        container.workouts.startWorkout(exerciseIds = listOf("plank"))
    }
    @After fun close() {
        compose.activityRule.scenario.close(); models.clear()
        container.close(); context.deleteDatabase(name)
    }
    private fun rows(date: LocalDate) = runBlocking { container.training.observeHistory(date, date).first() }
    @Test fun cancellationKeepsRecordsAndConfirmationClearsTodayAndStopsCurrentTraining() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-history").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open-history").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("history-clear-date") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-clear-date").performScrollTo().performClick()
        compose.onNodeWithText("其中有未结束训练", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(2, rows(LocalDate.now()).size)
        compose.onNodeWithTag("history-clear-date").performClick()
        compose.onNodeWithTag("confirm-clear-training-date").performClick()
        compose.waitUntil(10_000) { rows(LocalDate.now()).isEmpty() }
        compose.onNodeWithText("这一天还没有训练记录。").assertExists()
        compose.onNodeWithTag("history-clear-date").assertIsNotEnabled()
        assertNotNull(runBlocking { container.workouts.observeWorkout(yesterday).first() })
        assertNull(runBlocking { container.workouts.observeActiveWorkout().first() })
        assertNull(runBlocking { container.workouts.observeDailyWorkout(LocalDate.now()).first() })
        assertEquals(template, runBlocking { container.training.observeTemplates().first().single().id })
        compose.onNodeWithContentDescription("返回主页").performClick()
        compose.onNodeWithText("开始训练").assertExists()
    }
    @Test fun historicalDateCanBeClearedWithoutStoppingToday() {
        val model = HistoryViewModel(container); models.put("history", model)
        val date = LocalDate.now().minusDays(1)
        model.selectDate(date)
        compose.setContent { FitLogTheme { HistoryScreen(model, WeightUnit.KG, emptyList(), {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-clear-date").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("history-clear-date") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-clear-date").performScrollTo().performClick()
        compose.onNodeWithText("将删除 $date", substring = true).assertExists()
        compose.onNodeWithTag("confirm-clear-training-date").performClick()
        compose.waitUntil(10_000) { rows(date).isEmpty() }
        assertEquals(2, rows(LocalDate.now()).size)
        assertNotNull(runBlocking { container.workouts.observeActiveWorkout().first() })
    }
    @Test fun confirmationAlwaysUsesTheDateOriginallySelectedAndEmptyDatesAreDisabled() {
        val original = LocalDate.now().minusDays(10)
        var selected by mutableStateOf(original)
        var count by mutableIntStateOf(2)
        var result: LocalDate? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                FitLogTheme { ClearTrainingDayButton(selected, count, false, false) { result = it } }
            }
        }
        compose.onNodeWithTag("history-clear-date").performClick()
        compose.runOnIdle { selected = LocalDate.now() }
        compose.onNodeWithTag("confirm-clear-training-date").assertIsDisplayed().performClick()
        assertEquals(original, result)
        compose.runOnIdle { count = 0 }
        compose.onNodeWithTag("history-clear-date").assertIsNotEnabled()
    }
}
