package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeServiceRoutingTest {
    private fun server(i: Int) = Server("NL-$i", "vless", "host$i.example", 443, "uuid-$i")
    private fun rules(settings: Settings, routes: Map<String, List<String>>) = JSONObject(XrayConfigBuilder.vpnConfig(
        listOf(server(1) to null, server(2) to null), settings, 1081, serviceRoutes = routes))
        .getJSONObject("routing").getJSONArray("rules").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    @Test fun telegramDcIpsFollowMeasuredPinNotGenericBalancer() {
        val rules = rules(Settings(mode = Mode.SERVERS), mapOf("telegram" to listOf(server(2).id)))
        val ip = rules.first { it.optJSONArray("ip")?.toString()?.contains("geoip:telegram") == true }
        val domain = rules.first { it.optJSONArray("domain")?.toString()?.contains("geosite:telegram") == true }
        assertEquals("proxy-1", ip.getString("outboundTag"))
        assertEquals("proxy-1", domain.getString("outboundTag"))
    }
    @Test fun measuredVideoPinPrecedesGenericFastPool() {
        val rules = rules(Settings(mode = Mode.SERVERS), mapOf("youtube" to listOf(server(2).id)))
        val video = rules.first { it.optJSONArray("domain")?.toString()?.contains("googlevideo.com") == true }
        assertEquals("proxy-1", video.getString("outboundTag"))
    }
    @Test fun legacyHybridAndServiceScopeCannotRestoreDirectVideo() {
        val routes=mapOf("youtube" to listOf(server(2).id))
        val hybrid=rules(Settings(mode=Mode.HYBRID,hybridDomains=Settings.YT_DOMAINS),routes)
        val video=hybrid.first {it.optJSONArray("domain")?.toString()?.contains("googlevideo.com")==true}
        assertEquals("proxy-1",video.getString("outboundTag"))
        assertFalse(hybrid.any {it.optString("outboundTag")==XrayConfigBuilder.BYEDPI_TAG})
        val restricted=rules(Settings(mode=Mode.SERVERS,services=listOf("telegram")),routes)
        assertFalse(restricted.any {it.optString("outboundTag")=="direct" && it.optString("network")=="tcp,udp"})
    }
    @Test fun nativePinsRejectWebOnlyPassesAndUnknowns() {
        val yt = ServiceRoutes.targets.first { it.id == "youtube" }
        val tg = ServiceRoutes.targets.first { it.id == "telegram" }
        val web = Probe(10, null, null)
        assertNull(ServiceRoutes.measurement(yt, web)); assertNull(ServiceRoutes.measurement(tg, web))
        assertEquals(0, ServiceRoutes.measurement(yt, web.copy(ytOk = false)))
        assertEquals(0, ServiceRoutes.measurement(tg, web.copy(tgOk = false)))
        assertNull(ServiceRoutes.measurement(yt, web.copy(ytOk = true)))
        val fast = ServiceRoutes.measurement(yt, web.copy(ytOk = true, ytKbps = 20000))!!
        val slow = ServiceRoutes.measurement(yt, web.copy(ytOk = true, ytKbps = 1000))!!
        assertTrue(fast < slow)
        assertTrue(ServiceRoutes.measurement(tg, web.copy(tgOk = true))!! > 0)
    }
    @Test fun videoPoolNeverMixesVerySlowRouteWithFastMediaWinner() {
        assertEquals(listOf("fast"), ServiceRoutes.measuredChoices("youtube", listOf("fast" to 500, "slow" to 10000), "slow"))
        assertEquals(listOf("fast", "near"), ServiceRoutes.measuredChoices("youtube", listOf("fast" to 500, "near" to 600, "bad" to 0)))
        assertEquals(emptyList<String>(), ServiceRoutes.measuredChoices("youtube", listOf("bad" to 0)))
        val yt = ServiceRoutes.targets.first { it.id == "youtube" }
        assertEquals(0, ServiceRoutes.measurement(yt, Probe(0, null, true, ytKbps = 5000)))
    }
}
