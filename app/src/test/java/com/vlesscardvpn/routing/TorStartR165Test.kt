package com.vlesscardvpn.routing

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.tor.TorConnectionPolicy
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Start-path regressions: real Tor runtime has NO current ByeDPI port. */
class TorStartR165Test {
    private val saved = listOf(Server("fixture", "vless", "192.0.2.1", 443, "fixture") to null)
    private fun config(s: Settings, oldServers: Boolean = false) = JSONObject(
        XrayConfigBuilder.vpnConfig(if (oldServers) saved else emptyList(), s, null))
    private fun rules(c: JSONObject) = c.getJSONObject("routing").getJSONArray("rules").let { a ->
        (0 until a.length()).map(a::getJSONObject)
    }
    private fun assertTorOnly(c: JSONObject) {
        val outs=c.getJSONArray("outbounds").let { a -> (0 until a.length()).map(a::getJSONObject) }
        val tags=outs.map { it.getString("tag") }.toSet()
        assertTrue("Tor SOCKS outbound exists", "tor" in tags)
        assertFalse("No phantom ByeDPI outbound", "byedpi" in tags)
        assertFalse("Saved VLESS catalogue is not launched", tags.any { it.startsWith("proxy-") })
        assertFalse(c.getJSONObject("routing").has("balancers"))
        assertFalse(c.has("observatory")); assertFalse(c.has("burstObservatory"))
        rules(c).forEach { r -> if (r.has("outboundTag")) assertTrue("All outbound references resolve",r.getString("outboundTag") in tags) }
        assertEquals("tor",rules(c).last().getString("outboundTag"))
        assertEquals("tor",rules(c).first { it.optJSONArray("inboundTag")?.toString()?.contains("dns-module") == true && !it.has("ip") }.getString("outboundTag"))
    }
    @Test fun `actual Home Tor runtime starts without a ByeDPI process`() {
        val s=TorConnectionPolicy.runtime(TorConnectionPolicy.selectTor(Settings(mode=Mode.AUTO)))
        val c=config(s)
        assertTorOnly(c)
        java.io.File("/tmp/xcfg").mkdirs()
        java.io.File("/tmp/xcfg/vpn-tor-standalone.json").writeText(c.toString(2))
    }
    @Test fun `all saved modes allow standalone Tor with no servers or DPI`() {
        Mode.values().forEach { assertTorOnly(config(Settings(mode=it,torAll=true))) }
    }
    @Test fun `a saved server cannot make standalone Tor depend on masks or DPI`() {
        Mode.values().forEach { assertTorOnly(config(Settings(mode=it,torAll=true),oldServers=true)) }
    }
    @Test fun `standalone Tor keeps explicit direct exception`() {
        val c=config(Settings(torAll=true,alwaysDirect=listOf("example.test")))
        assertEquals("direct",rules(c).first { it.optJSONArray("domain")?.toString()?.contains("example.test") == true }.getString("outboundTag"))
    }
    @Test fun `RU direct remains an explicit saved exception with Tor and no servers`() {
        val c=config(Settings(torAll=true,ruDirect=true,ruDns=true))
        assertTrue(rules(c).any { it.optJSONArray("ip")?.optString(0)=="geoip:ru" && it.optString("outboundTag")=="direct" })
        assertTrue(rules(c).any { it.optJSONArray("ip")?.optString(0)==XrayConfigBuilder.RU_DNS && it.optString("outboundTag")=="direct" })
    }
    @Test fun `disabled RU direct cannot be reenabled by Tor`() {
        val c=config(Settings(torAll=true,ruDirect=false,ruDns=true))
        assertFalse(rules(c).any { it.optJSONArray("ip")?.optString(0) in listOf("geoip:ru",XrayConfigBuilder.RU_DNS) && it.optString("outboundTag")=="direct" })
    }
    @Test fun `Tor service routing alone does not waive required DPI engine`() {
        try {
            config(Settings(mode=Mode.BYEDPI,torRouting=true,services=listOf("discord")))
            fail("Regular ByeDPI mode still requires its process")
        } catch (e: IllegalArgumentException) { assertEquals("ByeDPI не запущен",e.message) }
    }
    @Test fun `ordinary ByeDPI mode still requires its process`() {
        try { config(Settings(mode=Mode.BYEDPI)); fail("Missing DPI must fail") }
        catch (e: IllegalArgumentException) { assertEquals("ByeDPI не запущен",e.message) }
    }
    @Test fun `ordinary Hybrid mode still requires its process`() {
        try { config(Settings(mode=Mode.HYBRID),oldServers=true); fail("Missing DPI must fail") }
        catch (e: IllegalArgumentException) { assertEquals("ByeDPI не запущен",e.message) }
    }
    @Test fun `ordinary Servers still requires a selected server`() {
        try { config(Settings(mode=Mode.SERVERS)); fail("Missing server must fail") }
        catch (e: IllegalArgumentException) { assertEquals("Не выбран ни один сервер",e.message) }
    }
}
