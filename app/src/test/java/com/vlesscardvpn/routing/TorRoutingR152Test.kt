package com.vlesscardvpn.routing

import com.vlesscardvpn.core.AutoEligibility
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tor-over-VLESS service routing + Auto catalog diversity (absorbed tor-rotator-vless, competitor-inspired). */
class TorRoutingR152Test {
    private fun server(i: Int, host: String = "host$i.example") = Server("NL-$i", "vless", host, 443, "uuid-$i")

    @Test fun fullTorUsesTheServerUplink() {
        val c=JSONObject(XrayConfigBuilder.vpnConfig(listOf(server(1) to null),Settings(mode=Mode.SERVERS,torAll=true),null))
        val outs=c.getJSONArray("outbounds").let {a->(0 until a.length()).map(a::getJSONObject)}
        assertTrue(outs.any {it.optString("tag")=="tor"})
        assertTrue(outs.any {it.optString("tag")=="proxy-0"})
        val rows=c.getJSONObject("routing").getJSONArray("rules").let {a->(0 until a.length()).map(a::getJSONObject)}
        assertEquals("proxy-0",rows.first {it.optJSONArray("inboundTag")?.toString()?.contains("tor-uplink")==true}.getString("outboundTag"))
        assertEquals("tor",rows.last {it.optString("network")=="tcp"}.getString("outboundTag"))
    }

    @Test fun withoutTorRoutingServicesRideTheMainProxy() {
        val s = Settings(mode = Mode.SERVERS, services = listOf("discord"), torRouting = false)
        val cfg = JSONObject(XrayConfigBuilder.vpnConfig(listOf(server(1) to null), s, null,
            serviceRoutes = mapOf("discord" to listOf(server(1).id))))
        assertFalse("no tor outbound when the flag is off", cfg.getJSONArray("outbounds").toString().contains("\"tor\""))
    }

    @Test fun autoKeepsAtMostOneServerPerSubnetAndSni() {
        val a = server(1, "203.0.113.7"); val b = server(2, "203.0.113.99")  // same /24
        val c = server(3, "198.51.100.4")                                     // different /24
        val sameSni = listOf(server(4).copy(sni = "cdn.example"), server(5).copy(sni = "cdn.example"))
        val kept = AutoEligibility.independent(listOf(a, b, c) + sameSni)
        val hosts = kept.map { it.address }.toSet()
        assertTrue("kept hosts were: $hosts", hosts.contains("203.0.113.7") xor hosts.contains("203.0.113.99"))
        assertTrue(hosts.contains("198.51.100.4"))
        assertTrue("one representative per SNI", kept.count { it.sni == "cdn.example" } <= 1)
        assertEquals(kept.size, kept.map { it.id }.toSet().size)
    }
}
