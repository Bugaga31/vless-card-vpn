package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Settings

/** Conservative starting heuristics, not carrier measurements or availability evidence.
 * Only runtime timing/filter settings change. Never grants Tor consent, rewrites bridges,
 * enables direct fallback, invents PASS or persists a runtime preset over manual values.
 */
object TorNetworkRecommendations {
    fun apply(settings: Settings, networkFamily: String): Settings {
        if (!settings.torNetworkRecommendations) return settings
        return when (networkFamily) {
            "Wi-Fi" -> settings.copy(torRotationSeconds = 600, torMaxLatencyMs = 2500)
            "Моб.: " -> settings // An incomplete key must not be interpreted as a carrier.
            else -> if (networkFamily.startsWith("Моб.: ") && networkFamily.removePrefix("Моб.: ").isNotBlank())
                settings.copy(torRotationSeconds = 900, torMaxLatencyMs = 5000)
                else settings
        }
    }
}
