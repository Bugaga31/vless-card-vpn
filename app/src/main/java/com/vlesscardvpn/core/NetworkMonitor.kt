package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager

object NetworkMonitor {
    data class NetworkInfo(
        val isMobile: Boolean,
        val isWifi: Boolean,
        val operatorName: String = "Unknown",
        val networkType: String = "Unknown"
    )

    fun getCurrentNetworkInfo(context: Context): NetworkInfo {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkInfo(false, false)
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkInfo(false, false)

        val isMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        var operatorName = "Unknown"
        var networkType = if (isWifi) "WiFi" else "Mobile"

        if (isMobile) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                operatorName = tm.networkOperatorName ?: "Unknown"
                networkType = when (tm.networkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    else -> "Mobile"
                }
            } catch (_: SecurityException) {
                // Permission not granted
            } catch (_: Exception) {
                // Ignore
            }
        }

        return NetworkInfo(isMobile, isWifi, operatorName, networkType)
    }

    fun getSniForNetwork(context: Context): String {
        val info = getCurrentNetworkInfo(context)
        return if (info.isMobile) "yandex.ru" else "samsung.com"
    }
}
