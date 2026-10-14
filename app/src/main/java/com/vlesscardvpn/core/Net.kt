package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/** Name of the current underlying network ("Моб.: Beeline", "Wi-Fi"): DPI strategies are remembered per network. */
object Net {
    fun key(context: Context): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "Нет сети"
            // Each Wi-Fi has its own DPI (home provider ≠ café ≠ work): told apart by the router address (no location permission needed).
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val link = cm.getLinkProperties(cm.activeNetwork)
                val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress.orEmpty()
                // SSID is OPTIONAL: when Android redacts it, do not request new location permissions.
                val ssid = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid }
                    .getOrNull()?.takeUnless { it == "<unknown ssid>" || it == "\"<unknown ssid>\"" }.orEmpty()
                val dns = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.sorted().joinToString(",")
                "Wi-Fi · WIFI_" + fingerprint(salt(context), ssid, gateway, dns)
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(TelephonyManager::class.java)
                val name = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: "оператор"
                val code = runCatching { tm?.networkOperator }.getOrNull().orEmpty().takeIf { it.matches(Regex("[0-9]{5,6}")) }
                "Моб.: " + name + " · CELL_" + (code ?: fingerprint(salt(context), name))
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Другая сеть"
        }
    }.getOrDefault("Сеть")

    /** Local salted identifier; raw SSID/gateway never becomes the persisted profile key. */
    fun fingerprint(salt: String, vararg fields: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((salt + "|" + fields.joinToString("|")).toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized private fun salt(ctx: Context): String {
        val p = ctx.getSharedPreferences("net_identity", Context.MODE_PRIVATE)
        return p.getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { check(p.edit().putString("salt", it).commit()) }
    }

    /** "Wi-Fi · 192.168.1.1" → "Wi-Fi": what was learned before 1.0.69 (all Wi-Fi as one) is still used as a start. */
    fun family(key: String): String = key.substringBefore(" · ")
}
