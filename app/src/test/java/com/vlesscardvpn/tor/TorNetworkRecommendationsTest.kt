package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic policy tests, not mobile-carrier or bridge reachability evidence. */
class TorNetworkRecommendationsTest {
    private val manual=Settings(torRotationSeconds=123,torMaxLatencyMs=100)
    private val enabled=manual.copy(torNetworkRecommendations=true)
    @Test fun oldProfilesStayManual() { assertFalse(Settings.fromJson(JSONObject()).torNetworkRecommendations) }
    @Test fun consentSurvivesExportAndImport() { assertTrue(Settings.fromJson(enabled.toJson()).torNetworkRecommendations) }
    @Test fun disabledRecommendationsKeepCustomHundredMs() {
        listOf("Wi-Fi","Моб.: оператор","Ethernet","Нет сети").forEach { assertEquals(manual,TorNetworkRecommendations.apply(manual,it)) }
    }
    @Test fun wifiHasConservativeStartingPreset() {
        val s=TorNetworkRecommendations.apply(enabled,"Wi-Fi")
        assertEquals(2500,s.torMaxLatencyMs);assertEquals(600,s.torRotationSeconds)
    }
    @Test fun mobileHasMoreTolerantStartingPreset() {
        val s=TorNetworkRecommendations.apply(enabled,"Моб.: оператор")
        assertEquals(5000,s.torMaxLatencyMs);assertEquals(900,s.torRotationSeconds)
    }
    @Test fun unknownAndOfflineDoNotGuessTransport() {
        listOf("Ethernet","Нет сети","Другая сеть","","Моб.: ","Wi-Fi-unknown").forEach { assertEquals(enabled,TorNetworkRecommendations.apply(enabled,it)) }
    }
    @Test fun returningToWifiDoesNotCarryMobileTimings() {
        val mobile=TorNetworkRecommendations.apply(enabled,"Моб.: оператор")
        assertEquals(TorNetworkRecommendations.apply(enabled,"Wi-Fi"),TorNetworkRecommendations.apply(mobile,"Wi-Fi"))
    }
    @Test fun runtimePresetDoesNotOverwriteSavedManualValues() {
        TorNetworkRecommendations.apply(enabled,"Wi-Fi")
        val saved=Settings.fromJson(enabled.toJson())
        assertEquals(123,saved.torRotationSeconds);assertEquals(100,saved.torMaxLatencyMs)
        assertEquals(manual,TorNetworkRecommendations.apply(saved.copy(torNetworkRecommendations=false),"Wi-Fi"))
    }
    @Test fun presetsCannotEnableTorRotationDirectRoutingOrChangeBridgesAndTls() {
        val s=enabled.copy(torAll=true,torBridge="obfs4",torBridgeLines="user supplied",alwaysDirect=listOf("example.test"))
        for(family in listOf("Wi-Fi","Моб.: оператор")) {
            val runtime=TorNetworkRecommendations.apply(s,family)
            assertEquals(s,runtime.copy(torRotationSeconds=s.torRotationSeconds,torMaxLatencyMs=s.torMaxLatencyMs))
        }
    }
}
