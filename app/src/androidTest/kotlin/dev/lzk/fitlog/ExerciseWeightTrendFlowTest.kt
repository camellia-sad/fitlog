package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
class ExerciseWeightTrendFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "weights-ui-" + UUID.randomUUID()
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        container = AppContainer(context, name); container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true))
    }
    @After fun close() { compose.activityRule.scenario.close(); container.close(); context.deleteDatabase(name) }
    private fun waitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun defaultWeightCanBeEditedFromDetailsAndUsedByFreeTraining() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("今日训练")
        compose.onNodeWithText("动作", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("exercise-search").performTextInput("杠铃卧推")
        waitText("杠铃卧推")
        compose.onNode(hasText("杠铃卧推", substring = false) and !hasSetTextAction()).performClick()
        compose.onNodeWithTag("edit-exercise-weight").performScrollTo().performClick()
        compose.onNodeWithTag("exercise-default-weight").performTextReplacement("37.5")
        compose.onNodeWithText("保存重量").performClick()
        compose.waitUntil(15_000) { runBlocking {
            container.workouts.observeExercises().first().single { it.id == "barbell-bench-press" }.defaultWeightGrams == 37_500L
        } }
        compose.onNodeWithText("加入待开始训练").performScrollTo().performClick()
        waitText("开始训练")
        compose.onNodeWithText("开始训练").performScrollTo().performClick()
        waitText("完成本组")
        val workout = runBlocking { container.workouts.observeActiveWorkout().first()!! }
        assertTrue(workout.exercises.single().sets.all { it.weightGrams == 37_500L })
    }

    @Test fun chartUsesDateRangesAndSupportsPinchPanAndReset() {
        val today = LocalDate.now()
        val points = listOf(300L, 200L, 100L, 20L, 0L).mapIndexed { i, ago ->
            PerformancePoint("s$i", today.minusDays(ago), 20_000L + i * 5_000, 8 + i)
        }
        compose.setContent { FitLogTheme { PerformanceChart(points, WeightUnit.KG) } }
        fun range(): String = compose.onNodeWithTag("performance-chart").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val all = range()
        compose.onNodeWithTag("performance-chart").performTouchInput {
            pinch(Offset(width * .4f, height * .5f), Offset(width * .6f, height * .5f),
                Offset(width * .15f, height * .5f), Offset(width * .85f, height * .5f), 500)
        }
        compose.waitForIdle()
        assertNotEquals(all, range())
        compose.onNodeWithTag("trend-range-30").performClick()
        val recent = range()
        compose.onNodeWithTag("performance-chart").performTouchInput { swipeRight(durationMillis = 500) }
        compose.waitForIdle()
        assertNotEquals(recent, range())
        compose.onNodeWithTag("trend-range-0").performClick()
        assertEquals(all, range())
        compose.onNodeWithTag("trend-zoom-in").performClick()
        assertNotEquals(all, range())
        compose.onNodeWithText("次数", substring = false).performClick()
        compose.onNodeWithText("每日最高次数").assertExists()
    }

    @Test fun exerciseDetailsOpenTheSameDateChart() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("今日训练")
        compose.onNodeWithText("动作", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("exercise-search").performTextInput("平板支撑")
        waitText("平板支撑")
        compose.onNode(hasText("平板支撑", substring = false) and !hasSetTextAction()).performClick()
        compose.onNodeWithTag("edit-exercise-weight").assertDoesNotExist()
        compose.onNodeWithText("查看动作历史").performScrollTo().performClick()
        waitText("还没有这个动作的已结束训练记录。")
        compose.onNodeWithContentDescription("返回动作库").performClick()
        waitText("动作库")
    }
}
