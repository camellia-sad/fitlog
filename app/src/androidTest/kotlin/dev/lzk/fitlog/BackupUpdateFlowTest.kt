package dev.lzk.fitlog

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.ui.*
import dev.lzk.fitlog.updates.AppUpdates
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupUpdateFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @After fun close(){compose.activityRule.scenario.close()}
    @Test fun backupPageKeepsLocalJsonAvailableWithoutLogin():Unit=runBlocking {
        val container=AppContainer(compose.activity,"backup-page-test.db")
        try {
            container.ready.await()
            compose.setContent {FitLogTheme {BackupScreen(container,{},{})}}
            compose.onNodeWithText("导出本地 JSON").assertIsEnabled()
            compose.onNodeWithText("导入本地 JSON").assertIsEnabled()
            compose.onNodeWithText("前往登录").assertExists()
            compose.onNodeWithText("云端备份包含聊天").assertExists()
        } finally {container.close()}
    }
    @Test fun updateEntryExplainsUnconfiguredEndpointAndKeepsAppUsable() {
        val updates=AppUpdates(compose.activity,"","update-empty-test")
        compose.setContent {FitLogTheme {UpdateEntry(updates)}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("检查更新 · "+BuildConfig.VERSION_NAME).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("检查更新 · "+BuildConfig.VERSION_NAME).performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("应用更新").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("应用更新").assertDoesNotExist()
    }
    @Test fun releaseRequiresHttpsBoundedSizeAndSha256() {
        fun valid()=JSONObject().put("versionCode",12).put("versionName","0.6.2").put("apkUrl","https://example.invalid/app.apk")
            .put("size",1234).put("sha256","a".repeat(64)).put("notes","更新说明")
        assertEquals(12,AppUpdates.parse(valid()).code.toInt())
        listOf(valid().put("apkUrl","http://example.invalid/app.apk"),valid().put("size",60_000_000),
            valid().put("sha256","bad"),valid().put("apkUrl","https://user:password@example.invalid/app.apk")).forEach {json->
            try {AppUpdates.parse(json);fail("bad release accepted")} catch(_:IllegalArgumentException) {}
        }
    }
}
