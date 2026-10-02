package com.vlesscardvpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.vlesscardvpn.domain.*
import java.security.MessageDigest

/** Records route/profile, not browsing history, SSID, credentials or destination URLs. */
class AdaptiveRouteMemory(context: Context) {
    private val prefs = context.getSharedPreferences("adaptive_routes", Context.MODE_PRIVATE)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private fun key(config: VlessConfig): String {
        val network = cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true }
        val caps = network?.let(cm::getNetworkCapabilities)
        val link = network?.let(cm::getLinkProperties)
        val type = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "wifi" else "other"
        val material = type + link?.interfaceName.orEmpty() + link?.dnsServers.orEmpty().joinToString { it.hostAddress.orEmpty() } + AutoConnectPolicy.identity(config)
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun get(config: VlessConfig): String? {
        val k = key(config)
        if (System.currentTimeMillis() - prefs.getLong("${k}_time", 0) > 7 * 86400_000L) return null
        return prefs.getString(k, null)
    }
    fun remember(config: VlessConfig, profile: RouteProfile, preset: ByeDpiPreset = ByeDpiPreset.COMBINED) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.endsWith("_time") }.sortedBy { prefs.getLong(it, 0) }.dropLast(63).forEach {
            editor.remove(it).remove(it.removeSuffix("_time"))
        }
        val k = key(config); editor.putString(k, if (profile == RouteProfile.BYEDPI) "BYEDPI#${preset.name}" else profile.name).putLong("${k}_time", System.currentTimeMillis()).apply()
    }
}
