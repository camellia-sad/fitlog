package dev.lzk.fitlog

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.data.NetworkDiagnostics
import dev.lzk.fitlog.features.SettingsViewModel
import dev.lzk.fitlog.ui.*
import dev.lzk.fitlog.updates.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.*
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class NetworkDiagnosticsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "net-ui-" + UUID.randomUUID()
    private lateinit var container: AppContainer
    private val models = ViewModelStore()
    @Before fun setup() = runBlocking<Unit> {
        container = AppContainer(context, namespace); container.ready.await()
        container.preferences.save(dev.lzk.fitlog.domain.UserPreferences(onboardingCompleted = true)); Unit
    }
    @After fun close() {
        compose.activityRule.scenario.close(); models.clear()
        container.networkDiagnostics.clear(); container.close(); context.deleteDatabase(namespace)
    }
    private fun records() = JSONObject(container.networkDiagnostics.exportJson()).getJSONArray("records")
    @Test fun exportedFileHasDiagnosticMetadataAndClearPreservesTrainingData() = runBlocking<Unit> {
        container.workouts.startWorkout()
        container.networkDiagnostics.begin("https://example.invalid/auth/v1/otp?email=PRIVATE_EMAIL", "POST", 15000, 15000)
            .failure(SocketException("ECONNRESET PRIVATE_TOKEN"))
        val model = SettingsViewModel(container); models.put("settings", model)
        val file = File(context.cacheDir, "network-export-test.json")
        try {
            model.exportNetworkLogs(context, Uri.fromFile(file)).join()
            assertEquals("网络调试日志已导出", model.message.value)
            val json = file.readText()
            assertEquals("FitLogNetworkDiagnostics", JSONObject(json).getString("format"))
            assertEquals("CONNECTION_RESET", JSONObject(json).getJSONArray("records").getJSONObject(0).getString("reason"))
            assertFalse(json.contains("PRIVATE"))
            model.clearNetworkLogs().join()
            assertEquals(0, records().length())
            assertNotNull(container.workouts.observeActiveWorkout().first())
        } finally { file.delete() }
    }
    @Test fun settingsExposeExportWithoutSigningIn() {
        compose.setContent { FitLogTheme { FitLogApp(container) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("main-navigation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNodeWithTag("export-network-logs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("clear-network-logs").assertExists()
    }
    @Test fun clearRequiresConfirmationAndWorksWithoutLogin() {
        container.networkDiagnostics.begin("https://example.invalid/auth/v1/otp", "POST", 1, 1).failure(IOException())
        val model = SettingsViewModel(container); models.put("settings", model)
        compose.setContent { FitLogTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NetworkDiagnosticsControls(false, model::exportNetworkLogs, model::clearNetworkLogs)
        } } }
        compose.onNodeWithTag("export-network-logs").assertIsEnabled()
        compose.onNodeWithTag("clear-network-logs").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, records().length())
        compose.onNodeWithTag("clear-network-logs").performClick()
        compose.onNodeWithText("确认清空日志").performClick()
        compose.waitUntil(10_000) { records().length() == 0 }
    }
    @Test fun updateChecksAndDownloadsRecordNetworkFailuresAndRemovePartialFiles() = runBlocking<Unit> {
        val original = SocketException("Connection reset PRIVATE")
        val url = "https://example.invalid/storage/v1/object/public/fitlog-updates/latest.json?token=PRIVATE"
        val updates = AppUpdates(context, url, namespace) { UpdateConnection(connectError = original) }
        try { updates.check(true); fail("reset ignored") } catch(e: IOException) { assertSame(original, e) }
        assertEquals("CONNECT", records().getJSONObject(0).getString("phase"))
        assertFalse(container.networkDiagnostics.exportJson().contains("PRIVATE"))
        val download = AppUpdates(context, url, namespace) { UpdateConnection(readError = true) }
        val release = AppRelease(BuildConfig.VERSION_CODE + 1L, "test", "https://example.invalid/PRIVATE_OBJECT.apk", 10, "0".repeat(64), "")
        try { download.download(release) {}; fail("reset ignored") } catch(_: IOException) {}
        val row = records().getJSONObject(1)
        assertEquals("READ", row.getString("phase"))
        assertEquals("CONNECTION_RESET", row.getString("reason"))
        assertEquals(1L, row.getLong("received_bytes"))
        assertFalse(container.networkDiagnostics.exportJson().contains("PRIVATE_OBJECT"))
        assertTrue(File(context.cacheDir, "updates").listFiles().orEmpty().none { it.extension == "part" })
    }
}
private class UpdateConnection(private val connectError: Exception? = null, private val readError: Boolean = false): HttpsURLConnection(URL("https://example.invalid")) {
    override fun connect() { connectError?.let { throw it } }
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getResponseCode() = 200
    override fun getHeaderField(name: String?): String? = null
    override fun getInputStream(): InputStream = if(!readError) ByteArrayInputStream("{}".toByteArray()) else object: InputStream() {
        private var sent = false
        override fun read(): Int = throw SocketException("ECONNRESET PRIVATE")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if(sent) throw SocketException("ECONNRESET PRIVATE")
            sent = true; b[off] = 1; return 1
        }
    }
}
