package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Settings

/** Conservative starting heuristics, not carrier measurements or availability evidence.
 * Only runtime timing/filter settings change. Never grants Tor consent, rewrites bridges,
 * enables direct fallback, invents PASS or persists a runtime preset over manual values.
 */
object TorNetworkRecommendations {
    const val WIFI_LATENCY_MS = 6000
    const val MOBILE_LATENCY_MS = 7500
    fun apply(settings: Settings, networkFamily: String): Settings {
        if (!settings.torNetworkRecommendations) return settings
        return when (networkFamily) {
            "Wi-Fi" -> settings.copy(torRotationSeconds = 600, torMaxLatencyMs = WIFI_LATENCY_MS)
            "Моб.: " -> settings // An incomplete key must not be interpreted as a carrier.
            else -> if (networkFamily.startsWith("Моб.: ") && networkFamily.removePrefix("Моб.: ").isNotBlank())
                settings.copy(torRotationSeconds = 900, torMaxLatencyMs = MOBILE_LATENCY_MS)
                else settings
        }
    }
}
