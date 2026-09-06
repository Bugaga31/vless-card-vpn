package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import com.vlesscardvpn.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NetworkType { WIFI, CELLULAR_MTS, CELLULAR_BEELINE, CELLULAR_MEGAFON, CELLULAR_TELE2, CELLULAR_OTHER, UNKNOWN }

data class EvaluatedNetworkProfile(
    val networkType: NetworkType,
    val optimalMtu: Int,
    val recommendedSni: String,
    val effectiveDns: String,
    val isFragmented: Boolean,
    val lastVerifiedTimestamp: Long = System.currentTimeMillis()
)

object NetworkProfileManager {
    private var cachedProfile: EvaluatedNetworkProfile? = null
    private var lastWorkingProfile: EvaluatedNetworkProfile? = null

    fun detectNetworkType(context: Context): NetworkType {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkType.UNKNOWN
            val activeNetwork = cm.activeNetwork ?: return NetworkType.UNKNOWN
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkType.UNKNOWN
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return NetworkType.WIFI
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val operatorName = (tm?.networkOperatorName ?: tm?.simOperatorName ?: "").lowercase()
                return when {
                    operatorName.contains("mts") || operatorName.contains("мтс") -> NetworkType.CELLULAR_MTS
                    operatorName.contains("beeline") || operatorName.contains("билайн") || operatorName.contains("veon") -> NetworkType.CELLULAR_BEELINE
                    operatorName.contains("megafon") || operatorName.contains("мегафон") || operatorName.contains("yota") -> NetworkType.CELLULAR_MEGAFON
                    operatorName.contains("tele2") || operatorName.contains("теле2") || operatorName.contains("t-mobile") || operatorName.contains("t2") -> NetworkType.CELLULAR_TELE2
                    else -> NetworkType.CELLULAR_OTHER
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkProfileManager", "Error detecting network type", e)
        }
        return NetworkType.UNKNOWN
    }

    suspend fun evaluateNetwork(
        context: Context,
        settings: AppSettings,
        serverExplicitSni: String = "",
        serverAddress: String = ""
    ): EvaluatedNetworkProfile = withContext(Dispatchers.IO) {
        val netType = detectNetworkType(context)
        // TCP connection failures and send buffer sizes are not path-MTU measurements.
        val testedMtu = settings.mtuSize.coerceIn(1280, 1500)
        val resolvedSni = when {
            serverExplicitSni.isNotBlank() -> serverExplicitSni.trim()
            settings.customSniOverride.isNotBlank() && !settings.customSniOverride.equals("auto", true) -> settings.customSniOverride.trim()
            else -> when (netType) {
                NetworkType.CELLULAR_MTS -> "mts.ru"
                NetworkType.CELLULAR_BEELINE -> "beeline.ru"
                NetworkType.CELLULAR_MEGAFON -> "megafon.ru"
                NetworkType.CELLULAR_TELE2 -> "tele2.ru"
                else -> "yandex.ru"
            }
        }
        val effectiveDns = when {
            settings.customDnsProvider.contains("Google", true) -> "https://8.8.8.8/dns-query"
            settings.customDnsProvider.contains("Yandex", true) -> "https://77.88.8.8/dns-query"
            else -> "https://1.1.1.1/dns-query"
        }
        EvaluatedNetworkProfile(netType, testedMtu, resolvedSni, effectiveDns, false).also { cachedProfile = it }
    }

    fun markProfileWorking(profile: EvaluatedNetworkProfile) { lastWorkingProfile = profile }
    fun rollbackProfile(): EvaluatedNetworkProfile? = lastWorkingProfile
    fun getCachedProfile(): EvaluatedNetworkProfile? = cachedProfile
}
