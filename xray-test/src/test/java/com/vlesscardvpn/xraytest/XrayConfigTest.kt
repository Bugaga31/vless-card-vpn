package com.vlesscardvpn.xraytest
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class XrayConfigTest {
    private val base = "vless://11111111-1111-4111-8111-111111111111@example.org:443"
    private fun reject(s: String) { try { XrayConfig.parse(s); fail("Must reject") } catch (_: ProfileError) {} }
    @Test fun realityIsPreserved() {
        val n = XrayConfig.parse("$base?security=reality&sni=example.net&pbk=test-key&sid=abcd&flow=xtls-rprx-vision")
        val j = JSONObject(XrayConfig.build(n, 10808))
        val ob = j.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("test-key", ob.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("publicKey"))
        assertEquals("xtls-rprx-vision", ob.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0).getString("flow"))
    }
    @Test fun noDirectFallback() {
        val j = JSONObject(XrayConfig.build(XrayConfig.parse("$base?security=tls"), 10808))
        assertEquals(1, j.getJSONArray("outbounds").length())
        assertEquals("vless", j.getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
    }
    @Test fun nativeTunAndLoopbackOnly() {
        val j = JSONObject(XrayConfig.build(XrayConfig.parse("$base?security=tls"), 10808)).getJSONArray("inbounds")
        assertEquals("tun", j.getJSONObject(0).getString("protocol"))
        assertEquals("127.0.0.1", j.getJSONObject(1).getString("listen"))
    }
    @Test fun rejectsUnencrypted() { reject(base) }
    @Test fun rejectsUnsupportedTransport() { reject("$base?security=tls&type=xhttp") }
    @Test fun rejectsIgnoredSecurityOptions() { reject("$base?security=tls&allowInsecure=1") }
    @Test fun rejectsRealityWithoutKey() { reject("$base?security=reality&sni=example.net") }
    @Test fun rejectsDuplicateParameters() { reject("$base?security=tls&security=reality") }
    @Test fun visionRequiresTcp() { reject("$base?security=tls&type=ws&flow=xtls-rprx-vision") }
    @Test fun rejectsInvalidShortId() { reject("$base?security=reality&sni=x&pbk=y&sid=xyz") }
    @Test fun deduplicatesInput() { assertEquals(1, XrayConfig.parseList("$base?security=tls\n$base?security=tls").size) }
    @Test fun ipv6Parses() { assertEquals("2001:db8::1", XrayConfig.parse(base.replace("example.org", "[2001:db8::1]") + "?security=tls").host) }
    @Test fun wsPathPreserved() {
        val j = JSONObject(XrayConfig.build(XrayConfig.parse("$base?security=tls&type=ws&path=%2Fx%3Fed%3D1"), 10808))
        assertEquals("/x?ed=1", j.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"))
    }
    @Test fun bothTargetsRequiredForAuto() { assertFalse(AutoPolicy.eligible(listOf(true, false))); assertTrue(AutoPolicy.eligible(listOf(true, true))); assertFalse(AutoPolicy.eligible(emptyList())) }
    @Test fun failureThresholdAndCooldown() { assertFalse(AutoPolicy.shouldSwitch(true, 2, 90000)); assertFalse(AutoPolicy.shouldSwitch(true, 3, 59999)); assertTrue(AutoPolicy.shouldSwitch(true, 3, 60000)); assertFalse(AutoPolicy.shouldSwitch(false, 9, 999999)) }
}
