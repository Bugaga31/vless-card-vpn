package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** R159: Discord voice survives the «only chosen services» catch-all; anti-tracker kills telemetry + DoT bypass. */
class DiscordPrivacyR159Test {
    private fun server(i: Int) = Server("NL-$i", "vless", "host$i.example", 443, "uuid-$i")
    private fun rules(settings: Settings, routes: Map<String, List<String>> = emptyMap()) = JSONObject(XrayConfigBuilder.vpnConfig(
        listOf(server(1) to null, server(2) to null), settings, 1081, serviceRoutes = routes))
        .getJSONObject("routing").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun discordVoiceUdpGoesThroughVpnNotDirect() {
        val r = rules(Settings(mode = Mode.SERVERS, services = listOf("discord")))
        val voice = r.filter { it.optString("port") == "50000-65535" && it.optString("network") == "udp" }
        assertTrue("voice rule missing: $r", voice.isNotEmpty())
        assertTrue(voice.none { it.optString("outboundTag") == "direct" })
        val direct = r.last { it.optString("outboundTag") == "direct" && it.optString("network") == "tcp,udp" }
        val idxVoice = r.indexOf(voice.first()); assertTrue(idxVoice < r.indexOf(direct))
    }

    @Test fun antiTrackerBlocksTelemetryAndDoTByDefault() {
        val r = rules(Settings(mode = Mode.SERVERS))
        val blocks = r.filter { it.optString("outboundTag") == "block" }
        assertTrue(blocks.any { it.optJSONArray("domain")?.toString()?.contains("app-measurement.com") == true })
        assertTrue(blocks.any { it.optString("port") == "853" })
        val off = rules(Settings(mode = Mode.SERVERS, blockTrackers = false))
        val offBlocks = off.filter { it.optString("outboundTag") == "block" }
        assertFalse(offBlocks.any { it.optJSONArray("domain")?.toString()?.contains("app-measurement.com") == true })
        assertFalse(offBlocks.any { it.optString("port") == "853" })
    }
}