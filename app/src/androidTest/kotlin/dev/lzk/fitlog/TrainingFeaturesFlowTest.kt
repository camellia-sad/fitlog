package dev.lzk.fitlog

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.FitLogApp
import dev.lzk.fitlog.ui.FitLogTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainingFeaturesFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fitlog-features-ui-test.db"
    private lateinit var container: AppContainer

    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        container = AppContainer(context, name)
        container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true, restSeconds = 90))
    }

    @After fun teardown() { compose.activityRule.scenario.close()
        container.close(); context.deleteDatabase(name) }

    private fun show(largeFont: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 2f else 1f)) {
                FitLogTheme(darkTheme = largeFont) { FitLogApp(container) }
            }
        }
        waitText("我的")
    }

    @Test fun templateStartsTrainingRestPauseFinishAndHistory() {
        val id = runBlocking { container.training.saveTemplate(TemplateDraft(name = "轻松推日", exercises = listOf(
            TemplateExercise("one", "barbell-bench-press", 0, 2, 8, 12, 37_500, 90)))) }
        show()
        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("轻松推日")
        compose.onNodeWithText("用于本次训练").performScrollTo().performClick()
        compose.onNodeWithText("开始训练", substring = false).performScrollTo().performClick()
        waitDescription("完成当前第1组")
        compose.onNodeWithText("暂停", substring = false).performClick()
        waitText("已暂停")
        compose.onNodeWithContentDescription("完成当前第1组").assertIsNotEnabled()
        compose.onNodeWithText("恢复", substring = false).performClick()
        waitText("暂停")
        compose.onNodeWithContentDescription("完成当前第1组").performClick()
        waitWorkout { it.completedSetCount == 1 && it.session.restEndsAtUtc != null }
        waitDescription("完成当前第2组")
        compose.onNodeWithTag("workout-rest").assertIsDisplayed()
        val restBounds = compose.onNodeWithTag("workout-rest").fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag("workout-exercise-card").fetchSemanticsNode().boundsInRoot
        assertTrue(restBounds.bottom <= cardBounds.top)
        compose.onNodeWithTag("workout-sets").performScrollToNode(hasContentDescription("输入第2组重量"))
        compose.onNodeWithTag("workout-rest").assertIsDisplayed()
        compose.onNodeWithText("跳过休息").performClick()
        waitWorkout { it.session.restEndsAtUtc == null }
        val sessionId = runBlocking { container.workouts.observeActiveWorkout().first()!!.session.id }
        compose.onNodeWithContentDescription("完成当前第2组").performClick()
        waitText("全部目标已完成")
        compose.onNodeWithText("训练感想（选填）").performTextInput("状态不错")
        compose.onNodeWithText("完成", substring = false).performClick()
        compose.onNodeWithTag("open-history").performClick()
        waitText("训练历史")
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("轻松推日 · 已完成"))
        compose.onNodeWithText("轻松推日 · 已完成").performClick()
        waitText("状态不错")
        compose.onNodeWithText("1.  37.5 kg × 8 · 已完成").assertExists()
        runBlocking {
            val saved = container.workouts.observeWorkout(sessionId).first()!!
            assertEquals(SessionStatus.FINISHED, saved.session.status)
            assertEquals(id, saved.session.templateId)
            assertEquals(37_500L, saved.exercises.single().sets.first().weightGrams)
            assertEquals(2, container.training.observeTemplates().first().single().exercises.single().targetSetCount)
        }
    }

    @Test fun templateEditorReordersChangesTargetsAndDeletesWithoutHistoryLoss() {
        val id = runBlocking { container.training.saveTemplate(TemplateDraft(name = "手动模板", exercises = listOf(
            TemplateExercise("a", "barbell-bench-press", 0),
            TemplateExercise("b", "lat-pulldown", 1)))) }
        show()
        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("手动模板")
        compose.onNodeWithText("编辑", substring = false).performClick()
        waitText("编辑模板")
        compose.onAllNodesWithText("下移").onFirst().performScrollTo().performClick()
        compose.onAllNodesWithText("调整").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("目标组数").performTextReplacement("2")
        compose.onNodeWithText("默认重量（kg）").performTextReplacement("42.5")
        compose.onNodeWithText("确认目标").performClick()
        compose.onNodeWithText("保存模板").performClick()
        waitText("模板已保存")
        runBlocking {
            val template = container.training.observeTemplates().first().single()
            assertEquals(id, template.id)
            assertEquals("lat-pulldown", template.exercises.first().exerciseId)
            assertEquals(2, template.exercises.first().targetSetCount)
            assertEquals(42_500L, template.exercises.first().defaultWeightGrams)
        }
        compose.onNodeWithText("删除", substring = false).performClick()
        waitText("删除模板？")
        compose.onAllNodesWithText("删除", substring = false).onLast().performClick()
        waitText("模板已删除")
        assertTrue(runBlocking { container.training.observeTemplates().first().isEmpty() })
    }

    @Test fun settingsAndLargeDarkTrainingKeepOriginalGrams() {
        runBlocking {
            val workout = container.workouts.startWorkout()
            val exercise = container.workouts.addExercise(workout.session.id, "barbell-bench-press", 1, 8, 12)
            val set = container.workouts.observeActiveWorkout().first()!!.exercises.single().sets.single()
            container.workouts.updateSetDraft(exercise, set.id, 37_500, 8)
        }
        show(largeFont = true)
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithText("lb", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("深色", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("保存设置", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { container.preferences.preferences.first().weightUnit == WeightUnit.LB } }
        assertEquals(ThemeMode.DARK, runBlocking { container.preferences.preferences.first().theme })
        compose.onNodeWithContentDescription("返回我的").performScrollTo().performClick()
        compose.onNodeWithText("主页", substring = false).performClick()
        waitText("继续训练")
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitDescription("完成当前第1组")
        compose.onNodeWithTag("workout-sets").performScrollToNode(hasContentDescription("输入第1组重量"))
        compose.onNodeWithContentDescription("输入第1组重量").assertIsDisplayed()
        val sessionId = runBlocking { container.workouts.observeActiveWorkout().first()!!.session.id }
        compose.onNodeWithContentDescription("完成当前第1组").performClick()
        waitText("全部目标已完成")
        assertEquals(37_500L, runBlocking { container.workouts.observeWorkout(sessionId).first()!!.exercises.single().sets.single().weightGrams })
    }

    @Test fun firstLaunchCanSkipPreferences() {
        runBlocking { container.preferences.save(UserPreferences()) }
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("跳过设置")
        compose.onNodeWithText("跳过设置").performClick()
        waitText("开始训练")
        assertTrue(runBlocking { container.preferences.preferences.first().onboardingCompleted })
    }

    private fun waitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitDescription(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().any {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
    }
    private fun waitWorkout(predicate: (Workout) -> Boolean) = compose.waitUntil(10_000) {
        runBlocking { container.workouts.observeActiveWorkout().first()?.let(predicate) == true }
    }
}
