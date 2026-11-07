package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fitlog-experience-test.db"
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        container = AppContainer(context,name)
        container.ready.await()
        container.preparation.savePreparedExercises(emptyList())
        container.preferences.save(UserPreferences(onboardingCompleted = true, restEnabled = false))
    }
    @After fun teardown() {
        compose.activityRule.scenario.close()
        container.close(); context.deleteDatabase(name)
    }
    private fun show() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("历史")
    }
    private suspend fun template() = container.training.saveTemplate(TemplateDraft(name = "自己的推日", exercises = listOf(
        TemplateExercise("one","barbell-bench-press",0,1,8,12,37_500,90))))

    @Test fun shareShortcutSavesReflectionBeforeGuestLoginPrompt() {
        val id=runBlocking {
            val workout=container.workouts.startWorkout()
            container.workouts.addExercise(workout.session.id,"barbell-bench-press",1,8,12)
            workout.session.id
        }
        show()
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitText("完成本组")
        compose.onNodeWithText("完成本组").performClick()
        waitText("全部目标已完成")
        compose.onNodeWithText("训练感想（选填）").performTextInput("记录今天的进步")
        compose.onNodeWithText("分享到动态").performClick()
        waitText("登录后分享到动态")
        assertEquals("记录今天的进步",runBlocking {container.workouts.observeWorkout(id).first()!!.session.feedback})
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("完成").performClick()
        waitText("历史")
    }

    @Test fun restPlanLeadsHomeAndWeekDayShowsSummary() {
        runBlocking { container.training.savePlan(TrainingPlan(LocalDate.now(), templateIds = List(7) { null })) }
        show()
        waitText("休息日")
        compose.onNodeWithText("今日计划", substring = false).assertExists()
        compose.onNodeWithText("可按需自由训练", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("week-day-${LocalDate.now().dayOfWeek.value - 1}").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("week-day-info").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("未训练", substring = false).assertExists()
    }

    @Test fun templatePickerSelectsTemplateAndClosesSheet() {
        val id = runBlocking { template() }
        show()
        compose.onNodeWithText("模板训练", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("template-picker").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("template-option-$id").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("template-picker").fetchSemanticsNodes().isEmpty() }
        waitText("自己的推日")
        compose.onNodeWithText("开始训练", substring = false).performScrollTo().performClick()
        waitDescription("完成当前第1组")
        assertEquals(id, runBlocking { container.workouts.observeActiveWorkout().first()!!.session.templateId })
    }

    @Test fun navigationUsesFourTabsAndIndependentHistoryAndTemplates() {
        show()
        compose.onNodeWithTag("main-navigation").assertExists()
        listOf("主页", "动作", "好友", "我的").forEach { label ->
            compose.onNode(hasText(label, substring = false) and hasAnyAncestor(hasTestTag("main-navigation"))).assertExists()
        }
        listOf("模板", "历史").forEach { label ->
            compose.onNode(hasText(label, substring = false) and hasAnyAncestor(hasTestTag("main-navigation"))).assertDoesNotExist()
        }
        compose.onNodeWithTag("open-history").performClick()
        waitText("训练历史")
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回主页").performClick()
        waitText("历史")
        compose.onNodeWithTag("open-history").performClick()
        waitText("训练历史")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("历史")

        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("exercise-search").performTextInput("卧推")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("训练模板")
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回动作库").performClick()
        waitText("动作库")
        compose.onNodeWithTag("exercise-search").assert(hasText("卧推"))
        compose.onNodeWithTag("open-templates").performClick()
        waitText("训练模板")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("动作库")
        compose.onNodeWithTag("main-navigation").assertExists()
        compose.onNodeWithText("我的", substring = false).performClick()
        waitText("设置")
        compose.onNodeWithTag("open-settings").performClick()
        waitText("保存设置")
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回我的").performClick()
        waitText("设置")
        compose.onNodeWithText("主页", substring = false).performClick()
        waitText("历史")
    }

    @Test fun preparationDoesNotStartTimerAndReturningHomePausesUntilContinue() {
        runBlocking {
            val id = template()
            container.training.savePlan(TrainingPlan(LocalDate.now(), templateIds = List(7) { id }))
        }
        show()
        waitText("自己的推日")
        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("exercise-search").performTextInput("Barbell Bench Press")
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("添加杠铃卧推").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("添加杠铃卧推").performClick()
        compose.waitUntil(10_000) { runBlocking { container.preparation.preparedExercises.first().isNotEmpty() } }
        assertNull(runBlocking { container.workouts.observeActiveWorkout().first() })
        compose.onNodeWithText("开始训练", substring = false).performScrollTo().performClick()
        waitDescription("完成当前第1组")
        assertNull(runBlocking { container.workouts.observeActiveWorkout().first()!!.session.templateId })
        compose.onNodeWithContentDescription("返回今日").performClick()
        compose.waitUntil(10_000) { runBlocking { container.workouts.observeActiveWorkout().first()?.session?.status == SessionStatus.PAUSED } }
        val paused = runBlocking { container.workouts.observeActiveWorkout().first()!! }
        assertEquals(paused.elapsedDuration(Instant.now()), paused.elapsedDuration(Instant.now().plusSeconds(600)))
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitDescription("完成当前第1组")
        compose.waitUntil(10_000) { runBlocking { container.workouts.observeActiveWorkout().first()?.session?.status == SessionStatus.ACTIVE } }
    }

    @Test fun addingActionStaysVisibleBeforeLongScrollableActionList() {
        runBlocking {
            val ids = container.workouts.observeExercises().first().take(15).map { it.id }
            container.workouts.startWorkout(exerciseIds = ids)
        }
        show()
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitText("展开")
        compose.onNodeWithTag("workout-exercises").performTouchInput { swipeLeft() }
        compose.onNodeWithText("结束训练").assertDoesNotExist()
        compose.onNodeWithTag("workout-exercises").performClick()
        compose.onNodeWithText("添加动作").assertIsDisplayed()
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithTag("workout-exercises").assertIsDisplayed()
    }

    @Test fun planEditorDefaultsToWeekAndSavesFourteenDayCycle() {
        val id = runBlocking { template() }
        show()
        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("设置健身计划")
        compose.onNodeWithText("设置健身计划").performClick()
        compose.onNodeWithText("周期天数（1～14）").assert(hasText("7"))
        compose.onNodeWithContentDescription("循环执行").assertIsOn()
        compose.onNodeWithText("两周").performClick()
        compose.onNodeWithTag("plan-day-0").performScrollTo().performClick()
        compose.onNodeWithText("自己的推日").performClick()
        compose.onNodeWithTag("plan-editor").performScrollToNode(hasTestTag("plan-day-13"))
        compose.onNodeWithTag("plan-day-13").performClick()
        compose.onNodeWithText("自己的推日").performClick()
        compose.onNodeWithText("保存计划").performClick()
        waitText("计划已保存")
        runBlocking {
            val plan = container.training.observePlan().first()!!
            assertEquals(14,plan.cycleDays)
            assertTrue(plan.repeats)
            assertEquals(id,plan.templateIds.first())
            assertEquals(id,plan.templateIds.last())
            assertNull(container.workouts.observeActiveWorkout().first())
        }
        // The timetable: cycle length in its own label, a flag on today, a cell per day that shows its summary.
        compose.onNodeWithContentDescription("14 天循环").assertExists()
        compose.onNodeWithTag("plan-strip").assertExists()
        val today = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY), java.time.LocalDate.now()).toInt()
        compose.onNodeWithTag("plan-cell-$today").assert(hasContentDescription("（今天）", substring = true))
        // The strip opens centred on today, so today's cell is the one on screen to tap.
        compose.onNodeWithTag("plan-cell-$today").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("plan-day-info").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("plan-day-info").assert(hasAnyDescendant(hasText(if (today == 0) "自己的推日" else "休息日", substring = true)))
        compose.onNodeWithTag("template-note-$id").assertExists()
    }

    @Test fun historyStartsAtCurrentMonthAndShowsIncompleteWithSingleLineFeeling() {
        val feeling = "今天的训练感想".repeat(40)
        runBlocking {
            val session = container.workouts.startWorkout()
            container.workouts.finishWorkout(session.session.id, feeling)
        }
        show()
        compose.onNodeWithTag("open-history").performClick()
        waitText("训练历史")
        if (LocalDate.now().monthValue > 1) compose.waitUntil(10_000) {
            compose.onNodeWithTag("history-calendar").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0
        }
        compose.onNodeWithText("颜色按已结束训练的完成组数", substring = true).assertDoesNotExist()
        listOf("0","1～3","4～8","9～15","16+").forEach { label ->
            compose.onNodeWithContentDescription("完成 $label 组的颜色").assertExists()
        }
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("自由训练 · 未完成"))
        compose.onNodeWithText("0 个完成组", substring = true).assertDoesNotExist()
        compose.onNodeWithText(feeling, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action(layouts)
            assertEquals(1, layouts.single().lineCount)
            assertTrue("Long feeling must visibly end in an ellipsis", layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun reducingGoalsToCompletedSetsShowsAutomaticSummary() {
        val id = runBlocking {
            val started = container.workouts.startWorkout()
            container.workouts.addExercise(started.session.id, "barbell-bench-press", 2, 8, 12)
            started.session.id
        }
        show()
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitDescription("完成当前第1组")
        compose.onNodeWithContentDescription("完成当前第1组").performClick()
        compose.waitUntil(10_000) { runBlocking { container.workouts.observeActiveWorkout().first()?.completedSetCount == 1 } }
        compose.onNodeWithContentDescription("减少目标组数").performClick()
        waitText("全部目标已完成")
        runBlocking {
            assertNull(container.workouts.observeActiveWorkout().first())
            assertTrue(container.workouts.observeWorkout(id).first()!!.allSetsCompleted)
        }
    }

    @Test fun onboardingTitleFitsOnOneLineAtNarrowPhoneWidth() {
        compose.setContent { FitLogTheme {
            Box(Modifier.width(262.dp)) { OnboardingScreen(UserPreferences()) {} }
        } }
        compose.onNodeWithText("一起找到你的节奏").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            action(layouts)
            assertEquals(1, layouts.single().lineCount)
            assertFalse(layouts.single().hasVisualOverflow)
        }
    }

    @Test fun deletingAllDataRequiresConfirmationAndCancelDoesNothing() {
        var calls = 0
        compose.setContent { FitLogTheme { PersonalDataDeleteButton { calls++; false } } }
        compose.onNodeWithText("清除全部本机数据").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0,calls)
        compose.onNodeWithText("清除全部本机数据").performClick()
        compose.onNodeWithText("永久删除").performClick()
        assertEquals(1,calls)
        compose.onNodeWithText("系统未能清除数据，请稍后重试。").assertExists()
    }

    private fun waitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitDescription(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().any {
            !it.config.contains(SemanticsProperties.Disabled)
        }
    }
}
