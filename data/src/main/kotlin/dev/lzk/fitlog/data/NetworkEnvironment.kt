package dev.lzk.fitlog.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

internal object NetworkEnvironment {
    @Suppress("DEPRECATION")
    fun device(context: Context): JSONObject = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        JSONObject().put("app_version", info.versionName).put("app_code", if(Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            .put("manufacturer", Build.MANUFACTURER.take(80)).put("model", Build.MODEL.take(80))
            .put("android_release", Build.VERSION.RELEASE.take(40)).put("android_api", Build.VERSION.SDK_INT)
    }.getOrElse { JSONObject().put("android_api", Build.VERSION.SDK_INT) }
    fun network(context: Context): JSONObject = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val links = network?.let { manager.getLinkProperties(it) }
        val transports = listOf(NetworkCapabilities.TRANSPORT_WIFI to "WIFI", NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_VPN to "VPN", NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH").filter { caps?.hasTransport(it.first) == true }.map { it.second }
        JSONObject().put("available", network != null).put("transports", JSONArray(transports))
            .put("internet", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("captive_portal", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
            .put("metered", manager.isActiveNetworkMetered)
            .put("proxy_configured", links?.httpProxy != null || !System.getProperty("https.proxyHost").isNullOrBlank())
            .put("private_dns", if(Build.VERSION.SDK_INT >= 28) links?.isPrivateDnsActive ?: false else JSONObject.NULL)
            .put("dns_servers", JSONArray(links?.dnsServers.orEmpty().take(4).mapNotNull { it.hostAddress }))
    }.getOrElse { JSONObject().put("available", JSONObject.NULL).put("collection", "unavailable") }
}
