package dev.lzk.fitlog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asImageBitmap
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
class TemplateShareFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fitlog-template-share-ui-test.db"
    private lateinit var container: AppContainer
    private lateinit var templateId: String
    private lateinit var bandId: String
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        container = AppContainer(context, name)
        container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true))
        val bench = container.workouts.observeExercises().first().first { it.nameZh == "杠铃卧推" }
        val band = container.training.createCustomExercise(CustomExerciseDraft("弹力带划船", "", EquipmentType.CABLE, setOf(Muscle.BACK)))
        bandId = band
        SaveTemplateUseCase(container.training)(TemplateDraft(null, "推日", SplitType.PUSH_PULL_LEGS, listOf(
            TemplateExercise("a", bench.id, 0, 4, 6, 10, 60_000, 120, false), TemplateExercise("b", band, 1)), "推"))
        templateId = container.training.observeTemplates().first().single().id
    }
    @After fun teardown() {
        compose.activityRule.scenario.close()
        container.close(); context.deleteDatabase(name)
    }
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private fun openTemplates() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        waitText("历史")
        compose.onNodeWithText("动作", substring = false).performClick()
        compose.onNodeWithTag("open-templates").performClick()
        waitTag("new-template")
    }

    @Test fun copiedTemplateIsOfferedOnANewTemplateAndUnknownExercisesAreCreatedOnSave() {
        openTemplates()
        compose.onNodeWithTag("copy-template-$templateId").performScrollTo().performClick()
        waitText("已复制")
        var copied = ""
        compose.runOnIdle {
            assertEquals(TemplateTransfer.CLIP_LABEL, clipboard.primaryClipDescription?.label)
            copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        }
        assertTrue(copied.contains(TemplateTransfer.FORMAT))
        // As on another phone: the custom exercise is unknown here.
        val elsewhere = copied.replace("弹力带划船", "壶铃摆荡").replace(bandId, "custom-from-another-phone")
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText(TemplateTransfer.CLIP_LABEL, elsewhere)) }
        compose.onNodeWithTag("new-template").performClick()
        waitTag("clipboard-template")
        compose.onNodeWithText("「推日」· 2 个动作").assertExists()
        compose.onNodeWithTag("clipboard-import").performClick()
        waitTag("import-notice")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pending-exercise-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pending-exercise-0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("推日（2）").assertExists()
        compose.onNodeWithTag("template-short-name").assertTextContains("推2")
        compose.onNodeWithTag("clipboard-template").assertDoesNotExist()
        // Importing alone saves nothing.
        runBlocking { assertTrue(container.workouts.observeExercises().first().none { it.nameZh == "壶铃摆荡" }) }
        compose.onNodeWithText("保存模板").performClick()
        compose.waitUntil(15_000) { runBlocking { container.training.observeTemplates().first().size == 2 } }
        runBlocking {
            val created = container.workouts.observeExercises().first().single { it.nameZh == "壶铃摆荡" }
            assertTrue(created.isCustom)
            val imported = container.training.observeTemplates().first().single { it.name == "推日（2）" }
            assertEquals(created.id, imported.exercises.sortedBy { it.sortOrder }[1].exerciseId)
            assertEquals(60_000L, imported.exercises.sortedBy { it.sortOrder }[0].defaultWeightGrams)
        }
    }

    @Test fun pasteNamesWhyTextIsNotATemplateAndNothingIsOfferedWithoutTheLabel() {
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("note", "今天练腿")) }
        openTemplates()
        compose.onNodeWithTag("new-template").performClick()
        waitTag("paste-template")
        compose.onNodeWithTag("clipboard-template").assertDoesNotExist()
        compose.onNodeWithTag("paste-template").performClick()
        waitText("剪贴板里不是 FitLog 模板")
    }

    @Test fun cropperReturnsTheFramedAreaInSourcePixels() {
        val source = android.graphics.Bitmap.createBitmap(1000, 500, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        var area: androidx.compose.ui.geometry.Rect? = null
        compose.setContent { FitLogTheme { BannerCropper(source, onCancel = {}) { area = it } } }
        waitTag("banner-crop-use")
        compose.onNodeWithTag("banner-crop-use").performClick()
        compose.waitUntil(5_000) { area != null }
        val framed = area!!
        assertEquals(1000f, framed.width, 1f)
        assertEquals(1000f / BANNER_RATIO, framed.height, 1f)
        assertEquals((500 - 1000f / BANNER_RATIO) / 2, framed.top, 1f)
    }
}
