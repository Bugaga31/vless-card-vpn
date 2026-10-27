package com.vlesscardvpn.routing

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Tor must not silently turn into a non-Tor route when servers/pins are absent. */
class TorStandaloneR163Test {
    private val servers = listOf(
        Server("fixture-a", "vless", "192.0.2.1", 443, "fixture-a"),
        Server("fixture-b", "vless", "198.51.100.1", 443, "fixture-b"))
    private fun config(s: Settings, pins: Map<String,List<String>> = emptyMap(), heavy: Set<Int> = emptySet()): JSONObject =
        JSONObject(XrayConfigBuilder.vpnConfig(if (s.mode == Mode.BYEDPI) emptyList() else servers.map { it to null },
            s, if (s.mode == Mode.SERVERS) null else 10801, serviceRoutes = pins, heavy = heavy))
    private fun rules(c: JSONObject): List<JSONObject> = c.getJSONObject("routing").getJSONArray("rules").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun default(c: JSONObject) = rules(c).last { it.optString("network") in setOf("tcp", "tcp,udp") }
    private fun discord(c: JSONObject) = rules(c).first { it.optJSONArray("domain")?.toString()?.contains("discord") == true }

    @Test(expected=IllegalArgumentException::class) fun `TorAll without a server is rejected`() {
        config(Settings(mode=Mode.BYEDPI, torAll=true))
    }
    @Test fun `legacy selected service scope cannot create a bypass`() {
        val c=config(Settings(mode=Mode.SERVERS,torRouting=true,services=listOf("discord")))
        assertEquals("balancer",default(c).getString("balancerTag"))
        assertFalse(c.getJSONArray("outbounds").toString().contains("\"tag\":\"tor\""))
    }
    @Test(expected=IllegalArgumentException::class) fun `selected services cannot restore serverless mode`() {
        config(Settings(mode=Mode.BYEDPI, torRouting=true, services=listOf("discord")))
    }
    @Test fun `TorAll is not limited by selected services`() {
        assertEquals("tor", default(config(Settings(torAll=true, services=listOf("discord")))).optString("outboundTag"))
    }
    @Test fun `learned pins cannot take precedence over TorAll`() {
        val c = config(Settings(torAll=true), mapOf("youtube" to listOf(servers[0].id)))
        assertTrue(rules(c).filter { it.optJSONArray("domain")?.toString()?.contains("youtube") == true && it.optString("outboundTag") != "block" }.all { it.optString("outboundTag") == "tor" })
    }
    @Test fun `heavy server pool cannot bypass TorAll`() {
        val c = config(Settings(torAll=true), heavy=setOf(0))
        assertFalse(rules(c).any { it.optString("balancerTag") == "heavy" })
    }
    @Test fun `TorAll is not overridden by hybrid DPI rules`() {
        val c = config(Settings(mode=Mode.HYBRID, torAll=true, hybridDomains=listOf("youtube.com")))
        assertFalse(rules(c).any { it.optString("outboundTag") == "byedpi" })
    }
    @Test(expected=IllegalArgumentException::class) fun `Tor off cannot restore serverless DPI`() {
        config(Settings(mode=Mode.BYEDPI))
    }
}
