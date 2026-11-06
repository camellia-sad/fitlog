package dev.lzk.fitlog.data

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/** Metadata only: never accepts request/response bodies, headers, tokens or raw exception messages. */
class NetworkDiagnostics(context: Context, namespace: String = "fitlog",
    private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val key = MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private val file = AtomicFile(File(app.noBackupFilesDir, "network-diagnostics/$key.jsonl"))
    @Volatile private var lastSuccess: Long? = null
    companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_RECORDS = 200
        const val RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private val gate = Any()
    }
    enum class Phase { OPEN, CONNECT, SEND, RESPONSE, READ, VALIDATE }
    fun begin(url: String, method: String, connectTimeout: Int, readTimeout: Int, sentBytes: Int = 0) =
        Attempt(url, method, connectTimeout, readTimeout, sentBytes)

    inner class Attempt internal constructor(url: String, method: String, connectTimeout: Int, readTimeout: Int, sentBytes: Int) {
        private val started = now()
        private val monotonic = SystemClock.elapsedRealtime()
        private val handled = AtomicBoolean()
        private var phase = Phase.OPEN
        private val timings = JSONArray()
        private var status: Int? = null
        private var serverRequestId: String? = null
        private var cipher: String? = null
        private var certificateSha256: String? = null
        var receivedBytes: Long = 0
        private val entry = safeEndpoint(url).put("attempt_id", UUID.randomUUID().toString())
            .put("timestamp", Instant.ofEpochMilli(started).toString()).put("timestamp_ms", started)
            .put("method", method.takeIf { it in setOf("GET","POST","PATCH","PUT","DELETE","HEAD") } ?: "OTHER")
            .put("connect_timeout_ms", connectTimeout).put("read_timeout_ms", readTimeout).put("sent_bytes", sentBytes)
            .put("device", NetworkEnvironment.device(app)).put("network_start", NetworkEnvironment.network(app))
        fun phase(value: Phase) {
            timings.put(JSONObject().put("phase", phase.name).put("elapsed_ms", elapsed()))
            phase = value
        }
        fun connected(connection: HttpsURLConnection) {
            cipher = runCatching { connection.cipherSuite }.getOrNull()?.takeIf { it.matches(Regex("[A-Z0-9_]{1,100}")) }
            certificateSha256 = runCatching {
                connection.serverCertificates.firstOrNull()?.encoded?.let {
                    MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
                }
            }.getOrNull()
        }
        fun response(connection: HttpsURLConnection, code: Int) {
            status = code
            serverRequestId = listOf("sb-request-id", "x-request-id").firstNotNullOfOrNull {
                runCatching { connection.getHeaderField(it) }.getOrNull()?.takeIf { id -> id.matches(Regex("[A-Za-z0-9_-]{8,100}")) }
            }
        }
        fun success() { if(handled.compareAndSet(false, true)) lastSuccess = now() }
        fun failure(error: Exception) {
            if(error is CancellationException || !handled.compareAndSet(false, true)) return
            // Diagnostics must never replace the original request failure.
            runCatching {
                val causes = causalChain(error)
                val reason = failureReason(causes, status)
                val safeStack = causes.take(4).map { cause ->
                    JSONObject().put("type", cause.javaClass.name)
                        .put("stack", JSONArray(cause.stackTrace.take(10).map { frame ->
                            frame.className.take(160) + "." + frame.methodName.take(100) + ":" + frame.lineNumber
                        }))
                }
                entry.put("phase", phase.name).put("elapsed_ms", elapsed()).put("phase_timeline", timings)
                    .put("status", status ?: JSONObject.NULL).put("server_request_id", serverRequestId ?: JSONObject.NULL)
                    .put("reason", reason).put("received_bytes", receivedBytes)
                    .put("cipher_suite", cipher ?: JSONObject.NULL).put("certificate_sha256", certificateSha256 ?: JSONObject.NULL)
                    .put("last_success_at", lastSuccess?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                    .put("network_failure", NetworkEnvironment.network(app)).put("exceptions", JSONArray(safeStack))
                append(entry)
            }
        }
        private fun elapsed() = (SystemClock.elapsedRealtime() - monotonic).coerceAtLeast(0)
    }

    fun exportJson(): String = synchronized(gate) {
        val rows = read()
        runCatching { persist(rows) }
        JSONObject().put("format", "FitLogNetworkDiagnostics").put("version", 1)
            .put("exported_at", Instant.ofEpochMilli(now()).toString())
            .put("device", NetworkEnvironment.device(app)).put("current_network", NetworkEnvironment.network(app))
            .put("retention_days", 7).put("max_bytes", MAX_BYTES)
            .put("records", JSONArray(rows)).toString(2)
    }
    fun clear() = synchronized(gate) { file.delete() }
    fun prune() = synchronized(gate) { persist(read()) }

    private fun append(entry: JSONObject) = synchronized(gate) {
        persist((read() + entry).takeLast(MAX_RECORDS))
    }
    private fun persist(records: List<JSONObject>) {
        if(records.isEmpty()) { file.delete(); return }
        val rows = records.toMutableList()
        var data = rows.joinToString("\n").toByteArray(Charsets.UTF_8)
        while(data.size > MAX_BYTES && rows.isNotEmpty()) {
            rows.removeAt(0); data = rows.joinToString("\n").toByteArray(Charsets.UTF_8)
        }
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try { output.write(data); file.finishWrite(output) }
        catch(e: Exception) { file.failWrite(output); throw e }
    }
    private fun read(): List<JSONObject> {
        if(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = runCatching { file.openRead().use { it.readBytesBounded(MAX_BYTES) } }.getOrDefault(byteArrayOf())
        val cutoff = now() - RETENTION_MILLIS
        return bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optLong("timestamp_ms") >= cutoff }.toList().takeLast(MAX_RECORDS)
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while(true) {
        val n = read(buffer)
        if(n < 0) return output.toByteArray()
        if(output.size() + n > limit) return byteArrayOf()
        output.write(buffer, 0, n)
    }
}

internal fun safeEndpoint(url: String): JSONObject {
    val uri = runCatching { URI(url) }.getOrNull()
    val segments = uri?.path.orEmpty().trim('/').split('/')
    val auth = setOf("otp","verify","token","logout","user")
    val rpc = setOf("list_fitlog_friends","find_fitlog_user","friend_action","social_chat","social_chat_relay",
        "social_feed","social_activity","social_training","social_person","social_blocks")
    val tables = setOf("social_profiles","friend_notes","social_privacy")
    val buckets = setOf("fitlog-media","fitlog-backups","fitlog-updates")
    val route = when {
        segments.take(2) == listOf("auth","v1") -> "/auth/v1/" + (segments.getOrNull(2)?.takeIf { it in auth } ?: "{operation}")
        segments.take(3) == listOf("rest","v1","rpc") -> "/rest/v1/rpc/" + (segments.getOrNull(3)?.takeIf { it in rpc } ?: "{operation}")
        segments.take(2) == listOf("rest","v1") -> "/rest/v1/" + (segments.getOrNull(2)?.takeIf { it in tables } ?: "{table}")
        segments.take(2) == listOf("functions","v1") -> "/functions/v1/" + (segments.getOrNull(2)?.takeIf { it == "account-delete" } ?: "{function}")
        segments.take(2) == listOf("storage","v1") -> "/storage/v1/" + (segments.firstOrNull { it in buckets } ?: "{bucket}") + "/{object}"
        else -> "/{path}"
    }
    return JSONObject().put("scheme", uri?.scheme?.takeIf { it in setOf("https","http") } ?: "unknown")
        .put("host", uri?.host?.take(253) ?: "unknown").put("port", uri?.port?.takeIf { it >= 0 } ?: 443).put("route", route)
}

private fun causalChain(error: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(6).toList()
}
private fun failureReason(causes: List<Throwable>, status: Int?): String {
    fun text(value: String) = causes.any { it.message.orEmpty().contains(value, ignoreCase = true) }
    return when {
        text("ECONNRESET") || text("connection reset") || text("reset by peer") -> "CONNECTION_RESET"
        causes.any { it is java.net.UnknownHostException } -> "DNS_FAILURE"
        causes.any { it is java.net.SocketTimeoutException } -> "TIMEOUT"
        causes.any { it is java.security.cert.CertPathValidatorException || it is java.security.cert.CertificateException } -> "TLS_CERTIFICATE"
        causes.any { it is javax.net.ssl.SSLHandshakeException } -> "TLS_HANDSHAKE"
        causes.any { it is javax.net.ssl.SSLException } -> "TLS_FAILURE"
        causes.any { it is java.net.ConnectException } -> "CONNECT_FAILED"
        text("ENETUNREACH") || text("network is unreachable") -> "NETWORK_UNREACHABLE"
        text("EPIPE") || text("broken pipe") -> "BROKEN_PIPE"
        status != null && status !in 200..299 -> "HTTP_ERROR"
        causes.any { it is java.io.IOException } -> "IO_FAILURE"
        else -> "CLIENT_FAILURE"
    }
}
