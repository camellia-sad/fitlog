package dev.lzk.fitlog.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.SocketException
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class NetworkDiagnosticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "network-" + UUID.randomUUID()
    private val logs = NetworkDiagnostics(context, namespace)
    @After fun clear() { logs.clear() }
    private fun records() = JSONObject(logs.exportJson()).getJSONArray("records")

    @Test fun actualOtpRequestRecordsResetAndSuccessfulContextWithoutCredentials() = runBlocking<Unit> {
        var reset: SocketException? = null
        val repo = SupabaseFriendsRepository(context, "https://example.invalid", "sb_publishable_PRIVATE_KEY", namespace) {
            FakeConnection().apply { connectError = reset }
        }
        repo.requestCode("PRIVATE_EMAIL@example.com")
        assertEquals(0, records().length())
        reset = SocketException("Connection reset by peer PRIVATE_EMAIL PRIVATE_TOKEN 123456")
        try { repo.requestCode("PRIVATE_EMAIL@example.com"); fail("request succeeded") } catch(e: IOException) { assertSame(reset, e) }
        val json = logs.exportJson()
        val record = records().getJSONObject(0)
        assertEquals("CONNECTION_RESET", record.getString("reason"))
        assertEquals("CONNECT", record.getString("phase"))
        assertEquals("/auth/v1/otp", record.getString("route"))
        assertEquals("POST", record.getString("method"))
        assertTrue(record.getLong("elapsed_ms") >= 0)
        assertFalse(record.isNull("last_success_at"))
        assertEquals(15_000, record.getInt("connect_timeout_ms"))
        assertTrue(record.getJSONObject("device").has("android_api"))
        listOf("PRIVATE_EMAIL", "PRIVATE_TOKEN", "PRIVATE_KEY", "123456", "create_user", "Authorization").forEach { assertFalse(it, json.contains(it)) }
    }
    @Test fun httpErrorsKeepStatusAndServerIdButDropResponseBody() = runBlocking<Unit> {
        val repo = SupabaseFriendsRepository(context, "https://example.invalid", "sb_publishable_test", namespace) {
            FakeConnection().apply { code = 429; body = """{"msg":"PRIVATE_SERVER_BODY@example.com"}""".toByteArray() }
        }
        try { repo.requestCode("PRIVATE_EMAIL@example.com"); fail("request succeeded") } catch(_: IOException) {}
        val record = records().getJSONObject(0)
        assertEquals(429, record.getInt("status"))
        assertEquals("HTTP_ERROR", record.getString("reason"))
        assertEquals("READ", record.getString("phase"))
        assertEquals("12345678-1234-abcd-0000-123456789abc", record.getString("server_request_id"))
        assertFalse(logs.exportJson().contains("PRIVATE_SERVER_BODY"))
    }
    @Test fun bodyReadResetKeepsReadPhaseAndReceivedCount() = runBlocking<Unit> {
        val repo = SupabaseFriendsRepository(context, "https://example.invalid", "sb_publishable_test", namespace) {
            FakeConnection().apply { readError = true }
        }
        try { repo.requestCode("PRIVATE_EMAIL@example.com"); fail("request succeeded") } catch(_: IOException) {}
        val record = records().getJSONObject(0)
        assertEquals("READ", record.getString("phase"))
        assertEquals("CONNECTION_RESET", record.getString("reason"))
        assertEquals(200, record.getInt("status"))
        assertEquals(3L, record.getLong("received_bytes"))
        assertEquals("TLS_AES_128_GCM_SHA256", record.getString("cipher_suite"))
    }
    @Test fun cancellationIsNotAnErrorAndStorageFailureNeverReplacesOriginalException() = runBlocking<Unit> {
        logs.begin("https://example.invalid/auth/v1/verify?token=PRIVATE", "POST", 1, 1).failure(CancellationException("PRIVATE"))
        assertEquals(0, records().length())
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val blocked = File(context.noBackupFilesDir, "network-diagnostics/$key.jsonl")
        blocked.mkdirs()
        val marker = File(blocked, "occupied").apply { writeText("test") }
        val original = SocketException("ECONNRESET PRIVATE")
        val repo = SupabaseFriendsRepository(context, "https://example.invalid", "sb_publishable_test", namespace) {
            FakeConnection().apply { connectError = original }
        }
        try { repo.requestCode("PRIVATE@example.com"); fail("request succeeded") } catch(e: IOException) { assertSame(original, e) }
        assertEquals(0, records().length())
        marker.delete(); blocked.delete()
    }
    @Test fun routeSanitizationRemovesUserIdsObjectsQueriesAndFragments() {
        val secrets = listOf(
            "https://user:PRIVATE_PASSWORD@example.invalid/rest/v1/social_profiles?id=eq.PRIVATE_ID#PRIVATE_FRAGMENT",
            "https://example.invalid/storage/v1/object/sign/fitlog-media/PRIVATE_ID/PRIVATE_PHOTO.jpg?token=PRIVATE_TOKEN",
            "https://example.invalid/rest/v1/rpc/PRIVATE_RPC?email=PRIVATE_EMAIL",
            "https://example.invalid/PRIVATE_PATH")
        secrets.forEach { url -> assertFalse(safeEndpoint(url).toString().contains("PRIVATE")) }
    }
    @Test fun concurrentInstancesRemainBoundedPersistAndPruneExpiredEntries() = runBlocking<Unit> {
        val time = AtomicLong(System.currentTimeMillis())
        val a = NetworkDiagnostics(context, namespace, time::get)
        val b = NetworkDiagnostics(context, namespace, time::get)
        coroutineScope {
            (0 until 4).map { worker -> async(Dispatchers.IO) {
                repeat(65) {
                    val error = IOException("PRIVATE")
                    error.stackTrace = emptyArray()
                    (if(worker % 2 == 0) a else b).begin("https://example.invalid/auth/v1/otp", "POST", 1, 1).failure(error)
                }
            } }.awaitAll()
        }
        var rows = JSONObject(a.exportJson()).getJSONArray("records")
        assertTrue(rows.length() in 1..NetworkDiagnostics.MAX_RECORDS)
        val huge = IOException("PRIVATE")
        huge.stackTrace = Array(10) { StackTraceElement("x".repeat(500), "y".repeat(500), "PRIVATE_FILENAME", it) }
        repeat(120) { a.begin("https://example.invalid/auth/v1/otp", "POST", 1, 1).failure(huge) }
        assertTrue(File(context.noBackupFilesDir, "network-diagnostics").listFiles().orEmpty().filter { it.isFile }.all { it.length() <= NetworkDiagnostics.MAX_BYTES })
        rows = JSONObject(b.exportJson()).getJSONArray("records")
        assertTrue(rows.length() in 1..NetworkDiagnostics.MAX_RECORDS)
        assertFalse(a.exportJson().contains("PRIVATE"))
        time.addAndGet(NetworkDiagnostics.RETENTION_MILLIS + 1)
        a.prune()
        assertEquals(0, JSONObject(a.exportJson()).getJSONArray("records").length())
        a.begin("https://example.invalid/auth/v1/otp", "POST", 1, 1).failure(IOException())
        assertEquals(1, JSONObject(a.exportJson()).getJSONArray("records").length())
        a.clear()
        assertEquals(0, JSONObject(b.exportJson()).getJSONArray("records").length())
    }
}

private class FakeConnection: HttpsURLConnection(URL("https://example.invalid")) {
    var connectError: Exception? = null
    var readError = false
    var code = 200
    var body = "{}".toByteArray()
    override fun connect() { connectError?.let { throw it } }
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getResponseCode() = code
    override fun getHeaderField(name: String?): String? = if(name == "sb-request-id") "12345678-1234-abcd-0000-123456789abc" else null
    override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
    override fun getInputStream(): InputStream = if(!readError) ByteArrayInputStream(body) else object: InputStream() {
        private var sent = false
        override fun read(): Int = throw SocketException("ECONNRESET PRIVATE_PAYLOAD")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if(sent) throw SocketException("ECONNRESET PRIVATE_PAYLOAD")
            sent = true
            byteArrayOf(1, 2, 3).copyInto(b, off)
            return 3
        }
    }
}
