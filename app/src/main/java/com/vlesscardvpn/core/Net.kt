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
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> cm.getLinkProperties(cm.activeNetwork)?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress?.let { "Wi-Fi · $it" } ?: "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(TelephonyManager::class.java)
                "Моб.: " + (tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName?.takeIf { it.isNotBlank() } ?: "оператор")
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Другая сеть"
        }
    }.getOrDefault("Сеть")

    /** "Wi-Fi · 192.168.1.1" → "Wi-Fi": what was learned before 1.0.69 (all Wi-Fi as one) is still used as a start. */
    fun family(key: String): String = key.substringBefore(" · ")
}
