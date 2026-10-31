package dev.lzk.fitlog

import android.content.Context
import androidx.activity.ComponentActivity
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

@RunWith(AndroidJUnit4::class)
class TemplateDraftFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fitlog-template-draft-ui-test.db"
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        container = AppContainer(context, name)
        container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true))
    }
    @After fun teardown() {
        compose.activityRule.scenario.close()
        container.close(); context.deleteDatabase(name)
    }
    private fun create() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("今日训练")
        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("新建")
        compose.onNodeWithText("新建").performClick()
        waitText("模板名称")
    }
    private fun pickerItem(name: String) = compose.onNode(hasText(name, substring = false) and hasAnyAncestor(hasTestTag("template-exercise-picker")))
    private fun addBench() {
        compose.onNodeWithText("添加模板动作").performScrollTo().performClick()
        compose.onNodeWithTag("template-exercise-search").performTextReplacement("Barbell Bench Press")
        pickerItem("杠铃卧推").performClick()
        waitText("调整")
    }

    @Test fun cancelKeepsDraftWithTargetsAndFinalSaveConsumesIt() {
        create()
        compose.onNodeWithText("模板名称").performTextInput("未写完的推日")
        addBench()
        compose.onNodeWithText("调整").performScrollTo().performClick()
        compose.onNodeWithText("目标组数").performTextReplacement("2")
        compose.onNodeWithText("默认重量（kg）").performTextReplacement("42.5")
        compose.onNodeWithText("确认目标").performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        waitText("模板还未保存")
        compose.onNodeWithText("继续编辑", substring = false).performClick()
        compose.onNodeWithText("模板名称").assert(hasText("未写完的推日"))
        assertTrue(runBlocking { container.training.observeTemplateDrafts().first().isEmpty() })
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithText("保留草稿并退出").performClick()
        waitText("草稿已保留")
        runBlocking {
            assertTrue(container.training.observeTemplates().first().isEmpty())
            val saved = container.training.observeTemplateDrafts().first().single()
            assertEquals("未写完的推日", saved.name)
            assertEquals(2, saved.exercises.single().targetSetCount)
            assertEquals(42_500L, saved.exercises.single().defaultWeightGrams)
        }
        compose.onNodeWithTag("resume-template-draft-new").performClick()
        waitText("模板名称")
        compose.onNodeWithText("模板名称").assert(hasText("未写完的推日"))
        compose.onNodeWithText("保存模板").performClick()
        waitText("模板已保存")
        runBlocking {
            assertTrue(container.training.observeTemplateDrafts().first().isEmpty())
            assertEquals(42_500L, container.training.observeTemplates().first().single().exercises.single().defaultWeightGrams)
        }
        compose.onNodeWithTag("resume-template-draft-new").assertDoesNotExist()
    }

    @Test fun systemBackAndPageExitOfferKeepOrDiscard() {
        create()
        compose.onNodeWithText("模板名称").performTextInput("准备练背")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("模板还未保存")
        compose.onNodeWithText("继续编辑", substring = false).performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitText("模板还未保存")
        compose.onNodeWithText("保留草稿并退出").performClick()
        waitText("模板草稿")
        compose.onNodeWithContentDescription("返回动作库").performClick()
        waitText("动作库")
        compose.onNodeWithText("主页", substring = false).performClick()
        waitText("今日训练")
        assertEquals("准备练背", runBlocking { container.training.observeTemplateDrafts().first().single().name })
        compose.onNodeWithText("动作", substring = false).performClick()
        waitText("动作库")
        compose.onNodeWithTag("open-templates").performClick()
        waitText("模板草稿")
        compose.onNodeWithTag("resume-template-draft-new").performClick()
        waitText("模板名称")
        compose.onNodeWithText("模板名称").performTextReplacement("改了一半的背日")
        compose.onNodeWithText("取消", substring = false).performClick()
        waitText("模板还未保存")
        compose.onNodeWithText("不保存并退出").performClick()
        waitText("训练模板")
        compose.onNodeWithContentDescription("返回动作库").performClick()
        waitText("动作库")
        runBlocking {
            assertTrue(container.training.observeTemplateDrafts().first().isEmpty())
            assertTrue(container.training.observeTemplates().first().isEmpty())
        }
    }

    @Test fun pickerSearchSupportsChineseEnglishAndClearWithoutReappearingText() {
        create()
        compose.onNodeWithText("添加模板动作").performScrollTo().performClick()
        val search = compose.onNodeWithTag("template-exercise-search")
        search.performTextInput("不存在的动作")
        compose.onNodeWithText("没有符合名称的动作，试试其他关键词。").assertExists()
        search.performTextReplacement("卧推")
        pickerItem("杠铃卧推").assertExists()
        compose.onNodeWithContentDescription("清空模板动作搜索").performClick()
        assertEquals("", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("50 个动作").assertExists()
        search.performTextInput("bArBeLl BeNcH pReSs")
        pickerItem("杠铃卧推").assertExists()
        search.performTextClearance()
        assertEquals("", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        search.performTextInput("Barbell Bench Press")
        pickerItem("杠铃卧推").performClick()
        waitText("调整")
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.onNodeWithText("保留草稿并退出").performClick()
        waitText("草稿已保留")
        val retained = runBlocking { container.training.observeTemplateDrafts().first().single() }
        assertEquals("", retained.name)
        assertEquals("barbell-bench-press", retained.exercises.single().exerciseId)
    }

    private fun waitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }
}
