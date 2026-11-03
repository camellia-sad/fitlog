package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DailyPlanFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "daily-ui-" + UUID.randomUUID()
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        container = AppContainer(context, name); container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true, restEnabled = false))
        val id = container.training.saveTemplate(TemplateDraft(name = "今日专属计划",
            exercises = listOf(TemplateExercise("a", "barbell-bench-press", 0, 2, 8, 12, 30_000))))
        container.workouts.reserveDailyWorkout(id)
        Unit
    }
    @After fun close() { compose.activityRule.scenario.close(); container.close(); context.deleteDatabase(name) }
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    private fun day() = runBlocking { container.workouts.observeDailyWorkout(LocalDate.now()).first()!! }
    private fun show() { compose.setContent { FitLogTheme { FitLogApp(container) } }; waitText("开始训练") }
    private fun complete() { waitText("完成本组"); compose.onNodeWithText("完成本组").performClick() }

    @Test fun endingHalfwayThenContinuingReusesProgressAndOneHistoryRecord() {
        val id = day().session.id
        show()
        assertNull(runBlocking { container.workouts.observeActiveWorkout().first() })
        compose.onNodeWithText("开始训练").performScrollTo().performClick()
        complete()
        compose.waitUntil(10_000) { day().completedSetCount == 1 }
        compose.onNodeWithText("结束", substring = false).performClick()
        waitText("结束训练"); compose.onNodeWithText("结束训练").performClick()
        waitText("本次训练已结束"); compose.onNodeWithText("完成", substring = false).performClick()
        waitText("继续今日计划"); compose.onNodeWithText("继续今日计划").performScrollTo().performClick()
        compose.waitUntil(10_000) { day().session.status == SessionStatus.ACTIVE }
        assertEquals(id, day().session.id)
        assertEquals(1, day().completedSetCount)
        complete()
        waitText("全部目标已完成"); compose.onNodeWithText("完成", substring = false).performClick()
        waitText("查看今日计划")
        assertEquals(2, day().completedSetCount)
        val history = runBlocking { container.training.observeHistory(LocalDate.now(), LocalDate.now()).first() }
        assertEquals(1, history.size)
    }

    @Test fun removingKeepsFactsAndFinishedActionCanGainMoreSetsInSamePlan() {
        show(); compose.onNodeWithText("开始训练").performScrollTo().performClick()
        complete(); compose.waitUntil(10_000) { day().completedSetCount == 1 }
        compose.onNodeWithTag("workout-exercises").performClick()
        compose.onNodeWithTag("remove-action-barbell-bench-press").performClick()
        compose.onNodeWithText("确认移除动作").performClick()
        compose.waitUntil(10_000) { day().exercises.single().removedFromPlan }
        assertEquals(1, day().completedSetCount)
        compose.onNodeWithTag("add-action-set-barbell-bench-press").performClick()
        compose.waitUntil(10_000) { day().plannedSetCount == 2 && !day().exercises.single().removedFromPlan }
        compose.onNodeWithText("收起").performClick()
        complete()
        waitText("全部目标已完成"); compose.onNodeWithText("完成", substring = false).performClick()
        compose.onNodeWithTag("home-today-actions").performScrollTo().performClick()
        compose.onNodeWithTag("add-action-set-barbell-bench-press").performClick()
        compose.waitUntil(10_000) { day().plannedSetCount == 3 }
        compose.onNodeWithText("收起").performClick()
        waitText("继续今日计划")
        assertEquals(2, day().completedSetCount)
        assertFalse(day().session.isExtra)
        assertEquals(1, runBlocking { container.training.observeHistory(LocalDate.now(), LocalDate.now()).first().size })
    }
}
