package com.vlesscardvpn.routing

import com.vlesscardvpn.model.*
import com.vlesscardvpn.tor.TorConnectionPolicy
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Start path: Tor requires a server uplink; legacy DPI-only settings cannot waive it. */
class TorStartR165Test {
    private val saved = listOf(Server("fixture", "vless", "192.0.2.1", 443, "fixture") to null)
    private fun config(s: Settings, withServer: Boolean = true) = JSONObject(XrayConfigBuilder.vpnConfig(if(withServer) saved else emptyList(), s, null))
    private fun rules(c: JSONObject) = c.getJSONObject("routing").getJSONArray("rules").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun rejected(s: Settings) {
        try { config(s, false); fail("Missing server must fail closed") }
        catch(e: IllegalArgumentException) { assertEquals("Для VPN нужен рабочий VLESS-сервер",e.message) }
    }
    private fun assertServerTor(c: JSONObject) {
        val outs=c.getJSONArray("outbounds").let { a -> (0 until a.length()).map(a::getJSONObject) }
        val tags=outs.map { it.getString("tag") }.toSet()
        assertTrue("tor" in tags); assertTrue("proxy-0" in tags); assertFalse("byedpi" in tags)
        rules(c).forEach { r -> if(r.has("outboundTag")) assertTrue(r.getString("outboundTag") in tags) }
        val uplink=rules(c).first { it.optJSONArray("inboundTag")?.toString()?.contains("tor-uplink")==true }
        assertEquals("proxy-0",uplink.getString("outboundTag"))
        assertEquals("tor", rules(c).last {it.optString("network")=="tcp"}.getString("outboundTag"))
        assertEquals("proxy-0",rules(c).last {it.optString("network")=="udp"}.getString("outboundTag"))
    }
    @Test fun homeTorStartsWithServerAndWithoutStandaloneDpi() { assertServerTor(config(TorConnectionPolicy.runtime(TorConnectionPolicy.selectTor(Settings())))) }
    @Test fun everySavedModeRequiresAnUplink() { Mode.values().forEach {rejected(Settings(mode=it,torAll=true))} }
    @Test fun everySavedModeCanUseTorBehindAServer() { Mode.values().forEach {assertServerTor(config(Settings(mode=it,torAll=true)))} }
    @Test fun savedPublicDirectExceptionCannotBypassTorUplink() {
        val c=config(Settings(torAll=true,alwaysDirect=listOf("example.test")))
        assertFalse(rules(c).any {it.optJSONArray("domain")?.toString()?.contains("example.test")==true && it.optString("outboundTag")=="direct"})
    }
    @Test fun savedRussianDirectSettingsCannotBypassServer() {
        val c=config(Settings(torAll=true,ruDirect=true,ruDns=true))
        assertFalse(rules(c).any {it.optString("outboundTag")=="direct" && it.optJSONArray("ip")?.toString()?.contains("geoip:ru")==true})
    }
    @Test fun disabledRussianDirectIsNeverReenabled() { assertServerTor(config(Settings(torAll=true,ruDirect=false,ruDns=true))) }
    @Test fun selectedServicesCannotCreateServerlessRoute() {rejected(Settings(mode=Mode.BYEDPI,torRouting=true,services=listOf("discord")))}
    @Test fun oldDpiModeWithoutServerFailsClosed() {rejected(Settings(mode=Mode.BYEDPI))}
    @Test fun oldHybridModeUsesServerRatherThanStandaloneDpi() {
        val c=config(Settings(mode=Mode.HYBRID))
        assertEquals("proxy-0",rules(c).last().getString("outboundTag"))
        assertFalse(c.getJSONArray("outbounds").toString().contains("\"tag\":\"byedpi\""))
    }
    @Test fun ordinaryServerModeRequiresSelectedServer() {rejected(Settings(mode=Mode.SERVERS))}
}
