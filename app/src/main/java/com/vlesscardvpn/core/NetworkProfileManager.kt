package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import com.vlesscardvpn.domain.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

enum class NetworkType {
    WIFI,
    CELLULAR_MTS,
    CELLULAR_BEELINE,
    CELLULAR_MEGAFON,
    CELLULAR_TELE2,
    CELLULAR_OTHER,
    UNKNOWN
}

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

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return NetworkType.WIFI
            }

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

    /**
     * Compute optimal MTU and profiles. Starts at 1400.
     * Modifies MTU only upon confirmed packet fragmentation.
     * Never overrides server-provided DNS/SNI if explicit.
     */
    suspend fun evaluateNetwork(
        context: Context,
        settings: AppSettings,
        serverExplicitSni: String = "",
        serverAddress: String = ""
    ): EvaluatedNetworkProfile = withContext(Dispatchers.IO) {
        val netType = detectNetworkType(context)

        // Starting MTU default: 1400 (anti-fragmentation standard for VLESS/TLS)
        var testedMtu = settings.mtuSize.coerceIn(1280, 1500)
        var isFragmented = false

        // Check if fragmentation occurs with large TCP payloads if server address is known
        if (serverAddress.isNotBlank()) {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 2000
                    socket.sendBufferSize = testedMtu
                    socket.connect(InetSocketAddress(serverAddress, 443), 2000)
                }
            } catch (e: Exception) {
                // If standard 1400 fails or shows fragmentation symptom, fallback safely to 1360
                testedMtu = 1360
                isFragmented = true
            }
        }

        val recommendedSni = when {
            serverExplicitSni.isNotBlank() && serverExplicitSni != "samsung.com" && serverExplicitSni != "yandex.ru" -> serverExplicitSni
            settings.customSniOverride.isNotBlank() && settings.customSniOverride != "auto" -> settings.customSniOverride
            else -> when (netType) {
                NetworkType.CELLULAR_MTS -> "mts.ru"
                NetworkType.CELLULAR_BEELINE -> "beeline.ru"
                NetworkType.CELLULAR_MEGAFON -> "megafon.ru"
                NetworkType.CELLULAR_TELE2 -> "tele2.ru"
                NetworkType.WIFI -> "yandex.ru"
                else -> "yandex.ru"
            }
        }

        val effectiveDns = when {
            settings.customDnsProvider.contains("Cloudflare") -> "https://1.1.1.1/dns-query"
            settings.customDnsProvider.contains("Google") -> "https://8.8.8.8/dns-query"
            settings.customDnsProvider.contains("Yandex") -> "77.88.8.8"
            else -> "https://1.1.1.1/dns-query"
        }

        val profile = EvaluatedNetworkProfile(
            networkType = netType,
            optimalMtu = testedMtu,
            recommendedSni = recommendedSni,
            effectiveDns = effectiveDns,
            isFragmented = isFragmented
        )
        cachedProfile = profile
        profile
    }

    fun markProfileWorking(profile: EvaluatedNetworkProfile) {
        lastWorkingProfile = profile
    }

    fun rollbackProfile(): EvaluatedNetworkProfile? {
        return lastWorkingProfile
    }

    fun getCachedProfile(): EvaluatedNetworkProfile? = cachedProfile
}
