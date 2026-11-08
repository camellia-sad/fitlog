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
    private lateinit var plan: String
    @Before fun setup() = runBlocking {
        container = AppContainer(context, name); container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true, restEnabled = false))
        plan = container.training.saveTemplate(TemplateDraft(name = "今日专属计划",
            exercises = listOf(TemplateExercise("a", "barbell-bench-press", 0, 2, 8, 12, 30_000))))
        container.training.savePlan(TrainingPlan(LocalDate.now(), templateIds = listOf(plan) + List(6) { null }))
    }
    @After fun close() { compose.activityRule.scenario.close(); container.close(); context.deleteDatabase(name) }
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun records() = runBlocking { container.workouts.observeDayRecords(LocalDate.now()).first() }
    private fun day() = records().single { it.session.daySlot == plan }
    private fun show() { compose.setContent { FitLogTheme { FitLogApp(container) } }; waitTag("main-navigation") }
    private fun complete() { waitText("完成本组"); compose.onNodeWithText("完成本组").performClick() }
    private fun pick(option: String) {
        waitTag("template-picker"); compose.onNodeWithTag(option).performClick()
    }

    @Test fun endingHalfwayThenContinuingReusesProgressAndOneHistoryRecord() {
        show()
        waitTag("home-start"); compose.onNodeWithTag("home-start").performScrollTo().performClick()
        pick("template-option-$plan")
        complete()
        compose.waitUntil(10_000) { day().completedSetCount == 1 }
        val id = day().session.id
        compose.onNodeWithText("结束", substring = false).performClick()
        waitText("结束训练"); compose.onNodeWithText("结束训练").performClick()
        waitText("本次训练已结束"); compose.onNodeWithText("完成", substring = false).performClick()
        waitTag("home-continue"); compose.onNodeWithTag("home-continue").performScrollTo().performClick()
        compose.waitUntil(10_000) { day().session.status == SessionStatus.ACTIVE }
        assertEquals(id, day().session.id)
        assertEquals(1, day().completedSetCount)
        complete()
        waitTag("plan-done"); compose.onNodeWithTag("plan-done-back").performClick()
        compose.onNodeWithTag("plan-review-banner").assertExists()
        compose.onNodeWithContentDescription("返回今日").performClick()
        // A finished template waits for an explicit choice.
        waitTag("home-start")
        assertEquals(2, day().completedSetCount)
        assertEquals(1, runBlocking { container.training.observeHistory(LocalDate.now(), LocalDate.now()).first() }.size)
    }

    @Test fun removingKeepsFactsAndFinishedActionCanGainMoreSetsInSamePlan() {
        show(); waitTag("home-start"); compose.onNodeWithTag("home-start").performScrollTo().performClick()
        pick("template-option-$plan")
        complete(); compose.waitUntil(10_000) { day().completedSetCount == 1 }
        compose.onNodeWithTag("workout-exercises").performClick()
        compose.onNodeWithTag("remove-action-barbell-bench-press").performClick()
        // Removal is confirmed inside the row itself.
        compose.onNodeWithTag("confirm-remove-barbell-bench-press").performClick()
        compose.waitUntil(10_000) { day().exercises.single().removedFromPlan }
        assertEquals(1, day().completedSetCount)
        compose.onNodeWithTag("add-action-set-barbell-bench-press").performClick()
        compose.waitUntil(10_000) { day().plannedSetCount == 2 && !day().exercises.single().removedFromPlan }
        compose.onNodeWithText("收起").performClick()
        complete()
        waitTag("plan-done"); compose.onNodeWithTag("plan-done-back").performClick()
        compose.onNodeWithContentDescription("返回今日").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-today-actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-today-actions").performScrollTo().performClick()
        compose.onNodeWithTag("add-action-set-barbell-bench-press").performScrollTo().performClick()
        compose.waitUntil(10_000) { day().plannedSetCount == 3 }
        compose.onNodeWithTag("home-today-actions").performScrollTo().performClick()
        waitTag("home-continue")
        assertEquals(2, day().completedSetCount)
        assertFalse(day().session.isExtra)
        assertEquals(1, runBlocking { container.training.observeHistory(LocalDate.now(), LocalDate.now()).first().size })
    }

    @Test fun switchingFromFreeTrainingAsksOnceAndKeepsItsSetsAsExtra() {
        val free = runBlocking {
            val w = container.workouts.startDailyWorkout(exerciseIds = listOf("plank"))
            val e = w.exercises.single()
            container.workouts.completeSet(e.id, e.sets.first().id, 0, 30)
            w.session.id
        }
        show()
        waitTag("home-switch"); compose.onNodeWithTag("home-switch").performScrollTo().performClick()
        pick("template-option-$plan")
        waitTag("switch-training")
        compose.onNodeWithTag("switch-cancel").performClick()
        assertEquals(free, runBlocking { container.workouts.observeActiveWorkout().first()!!.session.id })
        compose.onNodeWithTag("home-switch").performScrollTo().performClick()
        pick("template-option-$plan")
        waitTag("switch-confirm"); compose.onNodeWithTag("switch-confirm").performClick()
        compose.waitUntil(10_000) { runBlocking { container.workouts.observeActiveWorkout().first()?.session?.daySlot == plan } }
        val stopped = records().single { it.session.id == free }
        assertEquals(SessionStatus.FINISHED, stopped.session.status)
        assertEquals(1, stopped.completedSetCount)
        assertEquals("加练", stopped.session.trainingLabel)
        waitText("完成本组")
        // Back home both records are listed; tapping the extra one switches back without asking (nothing done yet here).
        compose.onNodeWithContentDescription("返回今日").performClick()
        waitTag("day-records")
        compose.onNodeWithTag("day-record-free").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { container.workouts.observeActiveWorkout().first()?.session?.id == free } }
        // The untouched plan record was dropped on the way out.
        assertEquals(listOf(free), records().map { it.session.id })
    }

    @Test fun templatePageStartGoesStraightToTraining() {
        show()
        compose.onNodeWithText("动作", substring = false).performClick()
        compose.onNodeWithTag("open-templates").performClick()
        waitTag("start-template-$plan")
        compose.onNodeWithTag("start-template-$plan").performScrollTo().performClick()
        waitText("完成本组")
        assertEquals(plan, runBlocking { container.workouts.observeActiveWorkout().first()!!.session.daySlot })
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
    }
}
