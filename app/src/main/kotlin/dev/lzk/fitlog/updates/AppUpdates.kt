package dev.lzk.fitlog.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.lzk.fitlog.BuildConfig
import dev.lzk.fitlog.data.NetworkDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class AppRelease(val code:Long,val version:String,val url:String,val size:Long,val sha256:String,val notes:String)
class AppUpdates(context:Context,private val manifestUrl:String,namespace:String,
    private val openConnection:(URI)->HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection }) {
    private val app=context.applicationContext
    private val settings=app.getSharedPreferences("$namespace.updates",Context.MODE_PRIVATE)
    private val folder=File(app.cacheDir,"updates").apply {mkdirs()}
    private val gate=Mutex()
    private val diagnostics = NetworkDiagnostics(app, namespace)
    fun snooze(release:AppRelease) {settings.edit().putLong("snooze_code",release.code).putLong("snooze_until",System.currentTimeMillis()+86_400_000).apply()}
    suspend fun check(manual:Boolean):AppRelease?=gate.withLock {
        if(!manual && System.currentTimeMillis()-settings.getLong("last_check",0)<6*60*60*1000) return null
        settings.edit().putLong("last_check",System.currentTimeMillis()).apply()
        val json=withContext(Dispatchers.IO) {
            monitored(manifestUrl) { c, trace ->
                trace.phase(NetworkDiagnostics.Phase.RESPONSE)
                val status = c.responseCode; trace.response(c, status)
                require(status==200) {if(status==404) "尚未发布更新版本" else "暂时无法检查更新"}
                trace.phase(NetworkDiagnostics.Phase.READ)
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                c.inputStream.use {input->
                    while(true) {val n=input.read(buffer);if(n<0) break;trace.receivedBytes += n;require(output.size()+n<=65536) {"版本信息过大"};output.write(buffer,0,n)}
                }
                JSONObject(output.toString("UTF-8"))
            }
        }
        val release=parse(json)
        settings.edit().putLong("last_check",System.currentTimeMillis()).apply()
        release.takeIf {it.code>BuildConfig.VERSION_CODE && (manual ||
            settings.getLong("snooze_code",0)!=it.code || System.currentTimeMillis()>settings.getLong("snooze_until",0))}
    }
    suspend fun download(release:AppRelease,onProgress:(Float)->Unit):File=withContext(Dispatchers.IO) {
        val temp=File.createTempFile("download-",".part",folder)
        val destination=File(folder,"FitLog-"+release.code+".apk")
        try { monitored(release.url) { c, trace ->
            trace.phase(NetworkDiagnostics.Phase.RESPONSE)
            val status = c.responseCode; trace.response(c, status)
            require(status==200) {"更新下载失败，请稍后重试"}
            val declared=c.contentLengthLong
            require(declared==-1L || declared==release.size) {"更新文件大小不匹配"}
            trace.phase(NetworkDiagnostics.Phase.READ)
            var count=0L
            c.inputStream.use {input->java.io.FileOutputStream(temp).use {output->
                val buffer=ByteArray(32768)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val n=input.read(buffer);if(n<0) break
                    count+=n;trace.receivedBytes=count;require(count<=release.size) {"更新文件大小不匹配"}
                    output.write(buffer,0,n);onProgress(count.toFloat()/release.size)
                }
                output.fd.sync()
            }}
            trace.phase(NetworkDiagnostics.Phase.VALIDATE)
            require(count==release.size && sha256(temp)==release.sha256) {"更新文件不完整，请重新下载"}
            verify(temp,release)
            check(temp.renameTo(destination)) {"无法保存更新安装包"}
            destination
        } } finally {temp.delete()}
    }
    fun install(file:File,release:AppRelease):Boolean {
        require(file.parentFile?.canonicalFile==folder.canonicalFile) {"安装包路径无效"}
        verify(file,release)
        if(!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri=FileProvider.getUriForFile(app,app.packageName+".updates",file)
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    @Suppress("DEPRECATION")
    internal fun verify(file:File,release:AppRelease) {
        require(file.length()==release.size && sha256(file)==release.sha256) {"更新文件已损坏"}
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=app.packageManager.getPackageArchiveInfo(file.path,flags) ?: error("安装包无效")
        val installed=app.packageManager.getPackageInfo(app.packageName,flags)
        val code=if(Build.VERSION.SDK_INT>=28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName==app.packageName && code==release.code && code>BuildConfig.VERSION_CODE) {"安装包版本或应用不匹配"}
        fun signatures(info:android.content.pm.PackageInfo)=
            (if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().map {it.toCharsString()}.toSet()
        require(signatures(installed).isNotEmpty() && signatures(installed)==signatures(archive)) {"更新签名不匹配，已停止安装"}
    }
    private suspend fun <T> monitored(url:String, action:suspend (HttpsURLConnection, NetworkDiagnostics.Attempt)->T):T {
        val trace = diagnostics.begin(url, "GET", 15_000, 30_000)
        var pending: HttpsURLConnection? = null
        try {
            val c = connection(url); pending = c
            trace.phase(NetworkDiagnostics.Phase.CONNECT)
            c.connect(); trace.connected(c)
            val result = action(c, trace)
            trace.success()
            return result
        } catch(error: Exception) { trace.failure(error); throw error }
        finally { pending?.disconnect() }
    }
    private fun connection(url:String):HttpsURLConnection {
        val uri=URI(url)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null) {"更新地址需要有效的 HTTPS 链接"}
        return openConnection(uri).apply {
            connectTimeout=15_000;readTimeout=30_000;instanceFollowRedirects=false
            setRequestProperty("Cache-Control","no-cache")
        }
    }
    companion object {
        fun parse(json:JSONObject):AppRelease {
            val release=AppRelease(json.getLong("versionCode"),json.getString("versionName"),json.getString("apkUrl"),
                json.getLong("size"),json.getString("sha256").lowercase(),json.optString("notes"))
            val uri=URI(release.url)
            require(release.code>0 && release.version.length in 1..80 && release.size in 1..52_428_800 &&
                release.sha256.matches(Regex("[a-f0-9]{64}")) && release.notes.length<=8000 &&
                uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null) {"版本信息无效"}
            return release
        }
        private fun sha256(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(32768);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
            return digest.digest().joinToString("") {"%02x".format(it)}
        }
    }
}
