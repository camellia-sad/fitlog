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

@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "fitlog-settings-ui-test.db"
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        container = AppContainer(context, name); container.ready.await()
        container.preferences.save(UserPreferences(onboardingCompleted = true, workoutMinutes = 45, equipment = setOf(EquipmentType.BARBELL)))
    }
    @After fun teardown() { compose.activityRule.scenario.close(); container.close(); context.deleteDatabase(name) }
    private fun prefs() = runBlocking { container.preferences.preferences.first() }

    @Test fun controlsSaveAsTheyChangeAndProfileCardJumps() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("main-navigation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("我的训练配置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("每次训练 45分钟，前往设置").assertExists()

        compose.onNodeWithContentDescription("增加训练时长").performScrollTo().performClick()
        compose.waitUntil(5_000) { prefs().workoutMinutes == 50 }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings-saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("每次训练 50分钟，前往设置").assertExists()

        compose.onNodeWithTag("equipment-dumbbell").performScrollTo().performClick()
        compose.waitUntil(5_000) { prefs().equipment == setOf(EquipmentType.BARBELL, EquipmentType.DUMBBELL) }

        compose.onNodeWithTag("settings-rest-value").performScrollTo().assertExists()
        compose.onNodeWithTag("settings-rest-switch").performClick()
        compose.waitUntil(5_000) { !prefs().restEnabled }
        compose.onNodeWithTag("settings-rest-value").assertDoesNotExist()
        compose.onNodeWithContentDescription("组间休息 关，前往设置").performScrollTo().assertExists()

        // Back to the top, then the jump strip (scrolled sideways to its last chip) brings the data group into view.
        compose.onNodeWithTag("settings-profile").performScrollTo()
        compose.onNodeWithTag("settings-jump-data").performScrollTo().performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("清空训练记录").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("清空训练记录").performClick()
        compose.onAllNodesWithText("会删除", substring = false, useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithText("会保留", substring = false, useUnmergedTree = true).onFirst().assertExists()
        compose.onNodeWithText("取消").performClick()
    }
}
