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
class BranchPagesFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "branch-ui-" + UUID.randomUUID()
    private lateinit var container: AppContainer
    private val bench = "barbell-bench-press"
    @Before fun setup() = runBlocking {
        container = AppContainer(context, name); container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true, restEnabled = false))
    }
    @After fun close() { compose.activityRule.scenario.close(); container.close(); context.deleteDatabase(name) }
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty() }
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun show() { compose.setContent { FitLogTheme { FitLogApp(container) } }; waitTag("main-navigation") }
    private fun day() = runBlocking { container.workouts.observeDailyWorkout(LocalDate.now()).first()!! }
    private fun best() = runBlocking { container.workouts.observeExercises().first().single { it.id == bench }.bestWeightGrams }

    @Test fun recordMedalStaysOnTheNewBestSetAndUndoTakesItAway() {
        runBlocking {
            val first = container.workouts.startWorkout()
            val a = container.workouts.addExercise(first.session.id, bench, 1, 8, 12)
            val set = container.workouts.observeActiveWorkout().first()!!.exercises.single().sets.single()
            container.workouts.completeSet(a, set.id, 25_000, 8)
            container.workouts.finishWorkout(first.session.id, null)
            val second = container.workouts.startWorkout()
            val b = container.workouts.addExercise(second.session.id, bench, 2, 8, 12)
            container.workouts.observeActiveWorkout().first()!!.exercises.single().sets.forEach { container.workouts.updateSetDraft(b, it.id, 30_000, 8) }
        }
        show()
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitText("完成本组"); compose.onNodeWithText("完成本组").performClick()
        waitTag("record-medal-1")
        compose.onNodeWithTag("record-medal-1").performClick()
        waitTag("record-bubble")
        compose.onNodeWithTag("record-bubble").assert(hasAnyDescendant(hasText("25")))
        // Leaving and coming back keeps the medal: it comes from the data, not the moment.
        compose.onNodeWithContentDescription("返回今日").performClick()
        compose.onNodeWithText("继续训练").performScrollTo().performClick()
        waitTag("record-medal-1")
        assertEquals(30_000L, best())
        compose.onNodeWithContentDescription("撤销第1组").performClick()
        compose.waitUntil(10_000) { best() == 25_000L }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("record-medal-1").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun finishedPlanStaysForCorrectionsAndExtraStartsWithoutHome() {
        runBlocking {
            val id = container.training.saveTemplate(TemplateDraft(name = "计划推日",
                exercises = listOf(TemplateExercise("a", bench, 0, 2, 8, 12, 30_000))))
            container.workouts.reserveDailyWorkout(id)
        }
        show()
        compose.onNodeWithText("开始训练").performScrollTo().performClick()
        repeat(2) { waitText("完成本组"); compose.onNodeWithText("完成本组").performClick(); compose.waitUntil(10_000) { day().completedSetCount == it + 1 } }
        waitTag("plan-done")
        compose.onNodeWithTag("plan-done-back").performClick()
        waitTag("plan-review-banner")
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        assertEquals(SessionStatus.FINISHED, day().session.status)
        // Correcting a mistaken set reopens the plan; finishing again asks again.
        compose.onNodeWithTag("workout-sets").performScrollToNode(hasContentDescription("撤销第2组"))
        compose.onNodeWithContentDescription("撤销第2组").performClick()
        compose.waitUntil(10_000) { day().session.status == SessionStatus.ACTIVE && day().completedSetCount == 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("plan-review-banner").fetchSemanticsNodes().isEmpty() }
        waitText("完成本组"); compose.onNodeWithText("完成本组").performClick()
        waitTag("plan-done")
        compose.onNodeWithTag("plan-done-extra").performClick()
        compose.waitUntil(10_000) { runBlocking { container.workouts.observeActiveWorkout().first()?.session?.isExtra == true } }
        waitText("先选一个动作吧")
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        assertEquals(SessionStatus.FINISHED, day().session.status)
        assertEquals(2, day().completedSetCount)
    }

    @Test fun planDayCanBePostponedAndUndoneFromTheBoard() {
        val id = runBlocking {
            val id = container.training.saveTemplate(TemplateDraft(name = "推迟测试", exercises = listOf(TemplateExercise("a", bench, 0))))
            container.training.savePlan(TrainingPlan(LocalDate.now(), templateIds = listOf(id) + List(6) { null }))
            id
        }
        show()
        waitText("推迟测试")
        compose.onNodeWithText("动作", substring = false).performClick()
        compose.onNodeWithTag("open-templates").performClick()
        waitTag("plan-postpone")
        compose.onNodeWithTag("plan-postpone").performClick()
        compose.waitUntil(10_000) { runBlocking { container.training.observePlan().first()!!.isPostponed(LocalDate.now()) } }
        val plan = runBlocking { container.training.observePlan().first()!! }
        assertNull(plan.templateOn(LocalDate.now()))
        assertEquals(id, plan.templateOn(LocalDate.now().plusDays(1)))
        waitTag("plan-undo-postpone")
        compose.onNodeWithContentDescription("返回动作库").performClick()
        compose.onNodeWithText("主页", substring = false).performClick()
        waitTag("home-postponed")
        compose.onNodeWithText("明天：推迟测试").assertExists()
        compose.onNodeWithText("动作", substring = false).performClick()
        compose.onNodeWithTag("open-templates").performClick()
        waitTag("plan-undo-postpone")
        compose.onNodeWithTag("plan-undo-postpone").performClick()
        compose.waitUntil(10_000) { runBlocking { container.training.observePlan().first()!!.postponedDates.isEmpty() } }
        waitTag("plan-postpone")
    }

    @Test fun customExercisePageSavesAssistingMusclesAndComesBackPickedInThePicker() {
        show()
        compose.onNodeWithText("动作", substring = false).performClick()
        compose.onNodeWithTag("open-custom-exercise").performClick()
        waitTag("custom-preview")
        compose.onNodeWithTag("main-navigation").assertDoesNotExist()
        compose.onNodeWithText("保存动作").performClick()
        waitTag("custom-muscle-error")
        compose.onNodeWithTag("custom-name").performTextInput("单臂划船")
        compose.onNodeWithTag("custom-preview").assert(hasAnyDescendant(hasText("单臂划船")))
        compose.onNodeWithTag("equipment-dumbbell").performScrollTo().performClick()
        compose.onNodeWithTag("custom-muscle-back").performScrollTo().performClick()
        compose.onNodeWithTag("custom-muscle-biceps").performScrollTo().performClick()
        compose.onNodeWithTag("custom-muscle-biceps").performClick()
        compose.onNodeWithTag("custom-muscle-biceps").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "辅助"))
        compose.onNodeWithTag("custom-seconds").performScrollTo().performClick()
        compose.onNodeWithText("保存动作").performClick()
        waitText("动作库")
        val made = runBlocking { container.workouts.observeExercises().first().single { it.nameZh == "单臂划船" } }
        assertEquals(setOf(Muscle.BACK), made.primaryMuscles)
        assertEquals(setOf(Muscle.BICEPS), made.secondaryMuscles)
        assertEquals(EquipmentType.DUMBBELL, made.equipmentType)
        assertEquals(RecordMetric.SECONDS, made.recordMetric)

        // From the picker, a new custom exercise returns already picked.
        compose.onNodeWithText("主页", substring = false).performClick()
        compose.onNodeWithText("添加本次动作").performScrollTo().performClick()
        waitTag("picker-custom")
        compose.onNodeWithTag("picker-custom").performClick()
        waitTag("custom-name")
        compose.onNodeWithTag("custom-name").performTextInput("靠墙静蹲")
        compose.onNodeWithTag("custom-muscle-quadriceps").performScrollTo().performClick()
        compose.onNodeWithText("保存动作").performClick()
        waitTag("picker-confirm")
        compose.onNodeWithContentDescription("已选 1 个动作").assertExists()
        compose.onNodeWithTag("picker-confirm").performClick()
        compose.waitUntil(10_000) { runBlocking { container.preparation.preparedExercises.first().size == 1 } }
    }

    @Test fun historyDateSheetPicksADayInTheMonthGrid() {
        show()
        compose.onNodeWithTag("open-history").performClick()
        waitTag("history-pick-date")
        compose.onNodeWithTag("history-pick-date").performClick()
        waitTag("date-sheet")
        val first = LocalDate.now().withDayOfMonth(1)
        compose.onNodeWithTag("date-$first").performClick()
        compose.onNodeWithTag("date-confirm").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("date-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("history-date").assert(hasContentDescription(first.toString()))
    }
}
