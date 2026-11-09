package dev.lzk.fitlog.data

import android.content.Context
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.io.ByteArrayOutputStream
import java.io.IOException

open class SupabaseFriendsRepository(context: Context, private val baseUrl: String, private val publicKey: String,
    namespace: String = "fitlog",
    private val openConnection: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }) : FriendsRepository {
    private val diagnostics = NetworkDiagnostics(context, namespace)
    override val configured = runCatching {
        val uri = URI(baseUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty().trim('/').isEmpty() &&
            publicKey.startsWith("sb_publishable_")
    }.getOrDefault(false)
    private val store = SocialSessionStore(context.applicationContext, namespace)
    private var session = store.read()?.let { runCatching { JSONObject(it) }.getOrNull() }
    private val _profile = MutableStateFlow(session?.optJSONObject("profile")?.let { runCatching { decodeProfile(it) }.getOrNull() })
    override val profile = _profile.asStateFlow()
    private val lock = Mutex()

    override suspend fun requestCode(email: String) {
        require(email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "请输入有效邮箱" }
        call("/auth/v1/otp", "POST", JSONObject().put("email", email.trim()).put("create_user", true))
    }
    override suspend fun verifyCode(email: String, code: String) = lock.withLock {
        require(code.matches(Regex("[0-9]{6,10}"))) { "请输入邮件中的验证码" }
        persist(JSONObject(call("/auth/v1/verify", "POST", JSONObject()
            .put("email", email.trim()).put("token", code).put("type", "email"))))
        loadProfile()
    }
    override suspend fun refreshProfile() = lock.withLock {
        if (session != null) { refreshToken(); loadProfile() }
    }
    override suspend fun saveProfile(nickname: String, bio: String) {
        require(nickname.trim().length in 1..40 && bio.length <= 300) { "昵称为 1～40 字，简介最多 300 字" }
        authorized("/rest/v1/social_profiles?id=eq.${requireProfileId()}", "PATCH",
            JSONObject().put("nickname", nickname.trim()).put("bio", bio))
        refreshProfile()
    }
    override suspend fun friends(): List<Friend> {
        val array = JSONArray(authorized("/rest/v1/rpc/list_fitlog_friends", "POST", JSONObject()))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Friend(decodeProfile(item), item.optString("note"), item.getString("status") == "accepted", item.getBoolean("incoming"),
                PresenceStatus.from(item.optString("presence")), item.optString("presence_updated_at").toSocialInstant(),
                item.optString("last_message").takeIf {it.isNotBlank() && it!="null"}, item.optString("last_message_at").toSocialInstant(), item.optInt("unread"))
        }
    }
    override suspend fun find(fitlogId: String): SocialProfile? {
        require(fitlogId.trim().matches(Regex("[a-fA-F0-9]{32}"))) { "请输入完整的 32 位 FitLog ID" }
        val value = authorized("/rest/v1/rpc/find_fitlog_user", "POST", JSONObject().put("p_fitlog_id", fitlogId.trim().lowercase()))
        return if (value.trim() == "null") null else decodeProfile(JSONObject(value))
    }
    override suspend fun act(peerId: String, action: String) {
        require(action in setOf("request","accept","reject","cancel","remove","block","unblock")) { "未知的好友操作" }
        authorized("/rest/v1/rpc/friend_action", "POST", JSONObject().put("p_peer", peerId).put("p_action", action))
    }
    override suspend fun saveNote(peerId: String, note: String) {
        require(note.length <= 40) { "备注最多 40 字" }
        authorized("/rest/v1/friend_notes?on_conflict=owner_id,peer_id", "POST",
            JSONObject().put("owner_id", requireProfileId()).put("peer_id", peerId).put("note", note),
            "resolution=merge-duplicates")
    }
    override suspend fun sharing(): SharingPreferences {
        val array = JSONArray(authorized("/rest/v1/social_privacy?owner_id=eq.${requireProfileId()}&select=share_history,share_live"))
        val value = array.getJSONObject(0)
        return SharingPreferences(value.getBoolean("share_history"), value.getBoolean("share_live"))
    }
    override suspend fun saveSharing(value: SharingPreferences) {
        authorized("/rest/v1/social_privacy?owner_id=eq.${requireProfileId()}", "PATCH",
            JSONObject().put("share_history", value.history).put("share_live", value.live))
    }
    override suspend fun signOut(): Unit = lock.withLock {
        val token = session?.optString("access_token")
        store.clear(); session = null; _profile.value = null
        if (!token.isNullOrEmpty()) try { call("/auth/v1/logout?scope=local", "POST", token = token) }
        catch (_: IOException) { }
        Unit
    }
    protected suspend fun forgetDeletedSession(owner:String) = lock.withLock {
        if(profile.value?.id==owner) {store.clear();session=null;_profile.value=null}
    }
    protected fun requireProfileId() = requireNotNull(profile.value?.id) { "请先登录" }
    protected suspend fun authorized(path: String, method: String = "GET", body: JSONObject? = null, prefer: String? = null,
        expectedOwner:String=requireProfileId()): String {
        return lock.withLock {
            check(profile.value?.id==expectedOwner) {"账号已切换"}
            refreshToken()
            try { call(path, method, body, requireNotNull(session).getString("access_token"), prefer) }
            catch (e: CloudException) {
                if (e.status == 401) { store.clear(); session = null; _profile.value = null }
                throw e
            }
        }
    }
    private suspend fun refreshToken() {
        val old = requireNotNull(session) { "请先登录" }
        if (old.optLong("expires_at") > System.currentTimeMillis() / 1000 + 60) return
        try {
            val value = JSONObject(call("/auth/v1/token?grant_type=refresh_token", "POST",
                JSONObject().put("refresh_token", old.getString("refresh_token"))))
            old.optJSONObject("profile")?.let { value.put("profile", it) }
            persist(value)
        } catch (e: CloudException) {
            if (e.status in setOf(400,401,403)) { store.clear(); session = null; _profile.value = null }
            throw e
        }
    }
    private suspend fun loadProfile() {
        val id = requireNotNull(session).getJSONObject("user").getString("id")
        // select=* so the profile still loads before a newer column (such as banner) is deployed.
        val array = JSONArray(call("/rest/v1/social_profiles?id=eq.$id&select=*",
            token = requireNotNull(session).getString("access_token")))
        require(array.length() == 1) { "云端好友表尚未初始化，请先部署数据库" }
        val value = array.getJSONObject(0)
        val updated = JSONObject(requireNotNull(session).toString()).put("profile", value)
        store.write(updated.toString()); session = updated; _profile.value = decodeProfile(value)
    }
    private fun persist(value: JSONObject) {
        if (!value.has("expires_at")) value.put("expires_at", System.currentTimeMillis()/1000 + value.getLong("expires_in"))
        store.write(value.toString()); session = value
    }
    private fun decodeProfile(value: JSONObject) = SocialProfile(value.getString("id"), value.getString("fitlog_id"),
        value.getString("nickname"), value.optString("bio"), value.optional("avatar_path"), value.optional("banner"))

    protected suspend fun authorizedBinary(path:String,method:String="GET",payload:ByteArray?=null,mime:String="image/jpeg",
        expectedOwner:String=requireProfileId(),maxBytes:Int=4_000_000):ByteArray {
        return lock.withLock {
            check(profile.value?.id==expectedOwner) {"账号已切换"}
            refreshToken()
            try {requestBytes(path,method,payload,requireNotNull(session).getString("access_token"),null,mime,maxBytes)}
            catch(e:CloudException) {
                if(e.status==401) {store.clear();session=null;_profile.value=null}
                throw e
            }
        }
    }

    private suspend fun call(path: String, method: String = "GET", body: JSONObject? = null,
        token: String? = null, prefer: String? = null): String =
        String(requestBytes(path,method,body?.toString()?.toByteArray(Charsets.UTF_8),token,prefer,"application/json; charset=utf-8"),Charsets.UTF_8)

    private suspend fun requestBytes(path:String,method:String,payload:ByteArray?,token:String?,prefer:String?,mime:String,maxBytes:Int=4_000_000):ByteArray = withContext(Dispatchers.IO) {
        check(configured) { "尚未配置云端连接" }
        val url = baseUrl.trimEnd('/') + path
        val trace = diagnostics.begin(url, method, 15_000, 15_000, payload?.size ?: 0)
        var pending: HttpsURLConnection? = null
        try {
            val connection = openConnection(URI(url)); pending = connection
            connection.connectTimeout=15_000; connection.readTimeout=15_000
            connection.instanceFollowRedirects=false; connection.requestMethod=method
            connection.setRequestProperty("apikey", publicKey)
            connection.setRequestProperty("Accept", "*/*")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            prefer?.let { connection.setRequestProperty("Prefer", it) }
            if(payload!=null) {
                connection.doOutput=true
                connection.setRequestProperty("Content-Type",mime)
            }
            trace.phase(NetworkDiagnostics.Phase.CONNECT)
            connection.connect(); trace.connected(connection)
            if(payload != null) {
                trace.phase(NetworkDiagnostics.Phase.SEND)
                connection.outputStream.use { it.write(payload) }
            }
            trace.phase(NetworkDiagnostics.Phase.RESPONSE)
            val status=connection.responseCode
            trace.response(connection, status)
            trace.phase(NetworkDiagnostics.Phase.READ)
            val bytes=(if(status in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {
                    val count=input.read(buffer)
                    if(count<0) break
                    require(output.size()+count<=maxBytes) {"云端响应过大"}
                    output.write(buffer,0,count); trace.receivedBytes += count
                }
                output.toByteArray()
            } ?: byteArrayOf()
            if(status !in 200..299) {
                val text=String(bytes,Charsets.UTF_8)
                val message=when(status) {
                    401 -> "登录已失效，请重新登录"
                    429 -> "操作过于频繁，请稍后再试"
                    else -> runCatching { JSONObject(text).optString("msg").ifBlank { JSONObject(text).optString("message") } }
                        .getOrDefault("").takeIf { it.isNotBlank() }?.take(300) ?: "云端请求失败，请稍后重试"
                }
                throw CloudException(status,message)
            }
            trace.success()
            bytes
        } catch(error: Exception) { trace.failure(error); throw error }
        finally { pending?.disconnect() }
    }
    private class CloudException(val status: Int,message:String): IOException(message)
}
