package com.vlesscardvpn
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class DirectStrategyTest {
    @Test fun planOrderCustomRememberedThenBuiltIn() {
        val custom = ByeDpiArgs.parse("-o1 -At,r,s -d1").getOrThrow()
        val plan = DirectStrategies.plan(custom, "TPWS#OOB", tpwsAvailable = true)
        assertEquals(DirectStrategies.CUSTOM_ID, plan[0].id)
        assertEquals(custom, plan[0].args)
        assertEquals("TPWS#OOB", plan[1].id)
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
        assertEquals(DirectStrategies.BUILT_IN.size + 1, plan.size)
        assertTrue(plan.any { it.engine == DpiEngine.TPWS })
    }
    @Test fun tpwsHiddenWhenBinaryMissing() {
        val plan = DirectStrategies.plan(null, "TPWS#OOB", tpwsAvailable = false)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.engine == DpiEngine.BYEDPI })
        assertEquals("BYEDPI#OOB_THEN_DISORDER", plan.first().id)
    }
    @Test fun unknownRememberedIgnored() {
        assertEquals(DirectStrategies.BUILT_IN.map { it.id }, DirectStrategies.plan(null, "garbage", true).map { it.id })
    }
    @Test fun argvKeepsListenerAppControlled() {
        val t = DirectStrategies.BUILT_IN.first { it.engine == DpiEngine.TPWS }.argv(20000)
        assertEquals(listOf("--socks", "--bind-addr=127.0.0.1", "--port=20000"), t.take(3))
        val b = DirectStrategies.VCARD_STEALTH.argv(20001, "vk.com")
        assertEquals(ByeDpiArgs.loopbackPrefix(20001), b.take(ByeDpiArgs.loopbackPrefix(20001).size))
        assertTrue("vk.com" in b); assertFalse(b.any { ByeDpiArgs.SNI_PLACEHOLDER in it })
        assertTrue(DirectStrategies.VCARD_STEALTH.masked)
        // The mask fake comes only after a DPI reset/timeout (--auto), never on the first attempt.
        assertTrue(b.indexOf("--auto=torst") in 0 until b.indexOf("--fake"))
    }
    @Test fun ttlFakesAreLast() {
        val ids = DirectStrategies.BUILT_IN.map { it.id }
        assertTrue(ids.indexOf("BYEDPI#MASK_FAKE_RAND") > ids.indexOf("BYEDPI#DISORDER"))
        assertTrue(ids.indexOf("BYEDPI#OOB_THEN_DISORDER") == 0)
    }
    @Test fun directConfigRoutesTcpToLocalSocksAndRejectsQuic() {
        val probe = LocalProbeProxy(port = 30001, username = "u", password = "p")
        val json = SingBoxManager.generateDirectConfig(null, AppSettings(), 30000, probe, platformSdk = 33)
        assertTrue(SingBoxManager.validateGeneratedConfig(json).isSuccess)
        val root = JSONObject(json)
        val outs = root.getJSONArray("outbounds")
        val proxy = (0 until outs.length()).map { outs.getJSONObject(it) }.first { it.getString("tag") == "proxy" }
        assertEquals("socks", proxy.getString("type")); assertEquals("127.0.0.1", proxy.getString("server"))
        assertEquals(30000, proxy.getInt("server_port")); assertEquals("tcp", proxy.getString("network"))
        assertFalse(json.contains("vless")); assertFalse(json.contains("uuid"))
        val route = root.getJSONObject("route"); assertEquals("proxy", route.getString("final"))
        val rules = route.getJSONArray("rules")
        assertEquals("probe-in", rules.getJSONObject(0).getJSONArray("inbound").getString(0))
        val quic = (0 until rules.length()).map { rules.getJSONObject(it) }.first { it.optString("action") == "reject" && it.optString("network") == "udp" }
        assertEquals(443, quic.getJSONArray("port").getInt(0))
        assertEquals("local-dns", root.getJSONObject("dns").getString("final"))
    }
    @Test fun youtubeBulkScoring() {
        val ok = TunnelProbe(TunnelHealthChecker.YOUTUBE_BULK_LABEL, 100, 200, 200)
        val yt = TunnelProbe("YouTube · HTTPS", 50, 204, 204)
        assertTrue(TunnelHealthReport(listOf(ok, yt)).youtubeBulk)
        assertFalse(TunnelHealthReport(listOf(yt)).youtubeBulk)
        assertTrue(TunnelHealthReport(listOf(ok, yt)).directScore > TunnelHealthReport(listOf(yt)).directScore)
        assertTrue(AppSettings().directFallback)
    }
    @Test fun ownMaskingFamily() {
        val ids = DirectStrategies.BUILT_IN.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(DirectStrategies.OWN.all { it.id in ids })
        assertEquals("BYEDPI#VCARD_CASCADE", ids[1])
        val c = DirectStrategies.VCARD_CASCADE.argv(20002, "max.ru")
        // Pool: user's mask first, every entry a separate --fake-sni, no placeholder left.
        val snis = c.indices.filter { c[it] == "--fake-sni" }.map { c[it + 1] }
        assertEquals("max.ru", snis.first()); assertTrue("vk.com" in snis); assertEquals(snis.size, snis.toSet().size)
        assertFalse(c.any { it.startsWith("{") })
        assertTrue(DirectStrategies.VCARD_CASCADE.masked && DirectStrategies.VCARD_CASCADE.adaptive)
        // Fakes only after two DPI failures.
        assertTrue(c.lastIndexOf("--auto=torst,ssl_err") in c.indexOf("--auto=torst,ssl_err") + 1 until c.indexOf("--fake"))
        assertFalse(DirectStrategies.VCARD_SHRED.adaptive || DirectStrategies.VCARD_SHRED.masked)
        assertEquals(DpiEngine.TPWS, DirectStrategies.VCARD_TPWS.engine)
        assertEquals(DirectStrategies.maskPool("ya.ru"), DirectStrategies.maskPool("ya.ru").distinct())
    }
}
