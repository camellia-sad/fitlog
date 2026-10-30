package dev.lzk.fitlog

import android.content.Context
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import dev.lzk.fitlog.ui.FitLogApp
import dev.lzk.fitlog.ui.FitLogTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDatabase = "fitlog-ui-test.db"
    private lateinit var container: AppContainer

    @Before fun setup() = runBlocking {
        context.deleteDatabase(testDatabase)
        container = AppContainer(context, testDatabase)
        container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true, restEnabled = false))
    }

    @After fun teardown() {
        compose.activityRule.scenario.close()
        container.close()
        context.deleteDatabase(testDatabase)
    }

    @Test fun recordThreeExercisesEditWeightSwitchUndoAndFinishOffline() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("开始训练").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("开始训练").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("选择动作").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.onNodeWithText("选择动作").performClick()
        addExercise("杠铃卧推")
        compose.onNodeWithContentDescription("输入第1组重量").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("37.5")
        compose.onNodeWithText("保存").performClick()
        waitFor { it.exercises.firstOrNull()?.sets?.firstOrNull()?.weightGrams == 37_500L }
        completeNext(1, 1)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("撤销第1组").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("撤销第1组").performClick()
        waitFor { it.completedSetCount == 0 }
        completeNext(1, 1)
        completeNext(2, 2)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("完成当前第3组").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("添加动作").performClick()
        addExercise("高位下拉")
        completeNext(1, 3)
        completeNext(2, 4)

        compose.onNodeWithText("添加动作").performClick()
        addExercise("杠铃深蹲")
        completeNext(1, 5)
        completeNext(2, 6)

        compose.onNodeWithText("杠铃卧推").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("杠铃卧推").fetchSemanticsNodes().size > 1 }
        compose.onNodeWithTag("workout-sets").performScrollToNode(hasContentDescription("输入第1组重量"))
        assertEquals(37_500L, runBlocking { container.workouts.observeActiveWorkout().first()!!.exercises.first().sets.first().weightGrams })
        compose.onNodeWithContentDescription("输入第1组重量").assertTextEquals("37.5")
        val id = runBlocking { requireNotNull(container.workouts.observeActiveWorkout().first()).session.id }
        compose.onNodeWithText("结束", substring = false).performClick()
        compose.onNodeWithText("结束训练", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("本次训练已结束").fetchSemanticsNodes().isNotEmpty() }
        val saved = runBlocking { requireNotNull(container.workouts.observeWorkout(id).first()) }
        assertEquals(SessionStatus.FINISHED, saved.session.status)
        assertEquals(3, saved.completedExerciseCount)
        assertEquals(6, saved.completedSetCount)
        assertEquals(37_500L, saved.exercises.first().sets.first().weightGrams)
        compose.onNodeWithText("完成", substring = false).performClick()
        compose.onNodeWithText("开始训练").assertExists()
    }

    private fun completeNext(index: Int, expectedCount: Int) {
        val description = "完成当前第${index}组"
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(description).performClick()
        waitFor { it.completedSetCount == expectedCount }
    }

    private fun addExercise(name: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("添加$name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("添加$name").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("完成本组").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(predicate: (Workout) -> Boolean) {
        compose.waitUntil(10_000) {
            runBlocking { container.workouts.observeActiveWorkout().first()?.let(predicate) ?: false }
        }
        compose.waitForIdle()
    }
}
