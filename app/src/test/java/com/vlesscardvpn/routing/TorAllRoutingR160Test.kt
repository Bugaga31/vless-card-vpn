package com.vlesscardvpn.routing

import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** «Весь трафик через Tor»: default route goes to the local Tor SOCKS; RU direct and block rules stay intact. */
class TorAllRoutingR160Test {
    private val reality = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp#Reality%20NL"

    private fun rules(cfg: String) = JSONObject(cfg).getJSONArray("rules").let { r -> (0 until r.length()).map { r.getJSONObject(it) } }

    @Test fun torAllRoutesDefaultThroughTor() {
        val cfg = JSONObject(XrayConfigBuilder.vpnConfig(listOf(LinkParser.parse(reality)!! to null), Settings(torAll = true, torRouting = true), null))
        val outbounds = cfg.getJSONArray("outbounds").let { o -> (0 until o.length()).map { o.getJSONObject(it).optString("tag") } }
        assertTrue("tor outbound present", "tor" in outbounds)
        val default = rules(cfg.toString()).last { it.has("network") && it.optString("network") == "tcp,udp" }
        assertEquals("tor", default.optString("outboundTag"))
    }

    @Test fun torOffKeepsNormalRoute() {
        val cfg = JSONObject(XrayConfigBuilder.vpnConfig(listOf(LinkParser.parse(reality)!! to null), Settings(), null))
        val default = rules(cfg.toString()).last { it.has("network") && it.optString("network") == "tcp,udp" }
        assertTrue("main proxy", default.optString("outboundTag").startsWith("proxy-"))
    }

    @Test fun torAllKeepsRuDirectAndBlock() {
        val cfg = XrayConfigBuilder.vpnConfig(listOf(LinkParser.parse(reality)!! to null), Settings(torAll = true, torRouting = true, ruDirect = true, blockAds = true, blockTrackers = true), null)
        val r = rules(cfg)
        assertTrue("ru direct", r.any { it.optJSONArray("ip")?.optString(0) == "geoip:ru" && it.optString("outboundTag") == "direct" })
        assertTrue("ads blocked", r.any { it.optString("outboundTag") == "block" })
        assertTrue("dns 853 blocked", r.any { it.optString("port") == "853" && it.optString("outboundTag") == "block" })
    }
}