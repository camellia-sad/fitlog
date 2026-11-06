package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.UserPreferences
import dev.lzk.fitlog.training.WorkoutUiState
import dev.lzk.fitlog.training.WorkoutViewModel
import dev.lzk.fitlog.ui.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SearchAndEmptyStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fitlog-search-empty-test.db"
    private lateinit var container: AppContainer
    private val models = ViewModelStore()

    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        container = AppContainer(context, name)
        container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true))
    }

    @After fun teardown() {
        compose.activityRule.scenario.close()
        models.clear()
        container.close()
        context.deleteDatabase(name)
    }

    @Test fun suppliedGifReplacesLibraryIconAndMuscleDiagramInDetail() {
        val model=WorkoutViewModel(container);models.put("gif",model)
        val item=dev.lzk.fitlog.domain.Exercise("barbell-bench-press","杠铃卧推","Bench",
            dev.lzk.fitlog.domain.EquipmentType.BARBELL,setOf(dev.lzk.fitlog.domain.Muscle.CHEST))
        val ui=WorkoutUiState(loading=false,exercises=listOf(item))
        // Details are a separate page now; the test shows it next to the library once the row is tapped.
        var open by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {FitLogTheme {
            ExerciseLibraryScreen(ui,model,{},onOpen={open=true})
            if(open) ExerciseDetailScreen(item,ui,model,onBack={},onHistory={},onAdd={})
        }}
        compose.waitUntil(15_000) {
            val pixels=compose.onNodeWithContentDescription("杠铃卧推动作演示").captureToImage().toPixelMap()
            val colors=mutableSetOf<androidx.compose.ui.graphics.Color>()
            for(y in 0 until pixels.height step 8)for(x in 0 until pixels.width step 8)colors.add(pixels[x,y])
            colors.size>4
        }
        compose.onNodeWithText("杠铃卧推").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithContentDescription("杠铃卧推动作演示").fetchSemanticsNodes().size==2}
        compose.onAllNodes(hasContentDescription("肌群",substring=true)).assertCountEquals(0)
    }

    @Test fun deletingAndEditingSearchSurviveLateQueryResults() {
        val model = WorkoutViewModel(container)
        models.put("search", model)
        model.search("ws")
        var response by mutableStateOf(WorkoutUiState(loading = false, searchQuery = "ws"))
        compose.setContent { FitLogTheme { ExerciseLibraryScreen(response, model, onAdd = {}) } }
        val input = compose.onNodeWithTag("exercise-search")
        input.performClick().performTextInputSelection(TextRange(2))
        input.performKeyInput { pressKey(Key.Backspace); pressKey(Key.Backspace) }
        input.assert(hasText(""))
        compose.runOnIdle { response = response.copy(searchQuery = "w") }
        input.assert(hasText(""))
        assertEquals("", model.currentSearchQuery)

        input.performTextInput("bench")
        input.performTextInputSelection(TextRange(2))
        input.performTextInput("X")
        compose.runOnIdle { response = response.copy(searchQuery = "bench") }
        input.assert(hasText("beXnch"))
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        compose.onNodeWithContentDescription("清空搜索").performClick()
        input.assert(hasText(""))
        assertEquals("", model.currentSearchQuery)
        compose.onNodeWithContentDescription("清空搜索").assertDoesNotExist()
    }

    @Test fun freshInstallHasNoProgressOrPlansAndTemplateStartsWithUserAddedActions() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("开始训练")
        compose.onNodeWithText("本周还没有训练记录").assertExists()
        compose.onNodeWithText("继续训练", substring = false).assertDoesNotExist()
        compose.onNodeWithText("本次进度").assertDoesNotExist()
        compose.onNodeWithText("本周训练", substring = true).assertDoesNotExist()
        runBlocking {
            assertNull(container.workouts.observeActiveWorkout().first())
            assertTrue(container.training.observeTemplates().first().isEmpty())
            assertTrue(container.training.observeHistory(LocalDate.of(1970, 1, 1), LocalDate.now()).first().isEmpty())
        }

        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("还没有训练模板")
        compose.onNodeWithText("简单分化建议").assertDoesNotExist()
        compose.onNodeWithText("创建第一个模板").performClick()
        compose.onNodeWithText("模板名称").performTextReplacement("我的训练安排")
        compose.onNodeWithText("保存模板").performClick()
        compose.onNodeWithText("模板须包含 1～30 个动作").assertExists()
        compose.onNodeWithText("添加模板动作").performClick()
        compose.onNodeWithTag("template-exercise-picker").performScrollToNode(hasText("杠铃卧推"))
        compose.onNodeWithText("杠铃卧推").performClick()
        compose.onNodeWithText("保存模板").performClick()
        waitText("模板已保存")
        runBlocking {
            val saved = container.training.observeTemplates().first().single()
            assertEquals("我的训练安排", saved.name)
            assertEquals(listOf("barbell-bench-press"), saved.exercises.map { it.exerciseId })
            assertNull(container.workouts.observeActiveWorkout().first())
        }
    }

    private fun waitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }
}
