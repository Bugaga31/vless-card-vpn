package com.vlesscardvpn

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.MaskLab
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.MyMasks
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class R70Test {
    private val pinned = Server("p", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "my.example", pcs = "AB:CD")
    private val ca = Server("c", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "my.example")
    private val reality = Server("r", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "reality", sni = "a.com", pbk = "k", flow = "xtls-rprx-vision")
    private val ws = Server("w", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", network = "ws", sni = "my.example", pcs = "AB")

    private fun tls(o: JSONObject) = o.getJSONObject("streamSettings").getJSONObject("tlsSettings")

    @Test fun signatureMasksInConfig() {
        val sni = Masks.byId("vc.sni.vk.com")!!
        assertEquals("brand", Masks.family(sni))
        assertTrue(Masks.compatible(sni, pinned)); assertFalse(Masks.compatible(sni, ca)); assertFalse(Masks.compatible(sni, reality))
        assertEquals("vk.com", tls(XrayConfigBuilder.outbound(pinned, "p", sni)).getString("serverName"))
        assertEquals("my.example", tls(XrayConfigBuilder.outbound(ca, "p", sni)).getString("serverName")) // never on a CA cert

        val ghost = Masks.byId("vc.ghost")!!
        val o = XrayConfigBuilder.outbound(ca, "p", ghost)
        assertEquals(300, o.getJSONObject("streamSettings").getJSONObject("sockopt").getInt("tcpMaxSeg"))
        assertTrue(o.has("mux"))
        assertEquals("h2", tls(o).getJSONArray("alpn").getString(0))
        assertFalse(Masks.compatible(ghost, reality)) // vision: no mux
        assertTrue(Masks.compatible(Masks.byId("vc.mss300")!!, reality))
        assertFalse(Masks.compatible(Masks.byId("vc.alpn.br")!!, ws)); assertTrue(Masks.compatible(Masks.byId("vc.alpn.11")!!, ws))
        assertTrue(Masks.searchOrder(true, pinned).take(12).count { it.signature } >= 5)
    }

    @Test fun signatureLinksAndEvolution() {
        val m = MyMasks.fork(Masks.byId("vc.ghost.sni")!!, "Невидимка для МТС")
        val back = MyMasks.decode(MyMasks.encode(m))
        assertEquals(listOf("vk.com", "", "120", "true"), listOf(back.sni, back.alpn, back.mss.toString(), back.mux.toString()))
        assertEquals(m.id, back.id)
        val bad = runCatching { MyMasks.fromJson(JSONObject().put("fp", "chrome").put("mss", 20)) }
        assertTrue(bad.isFailure)
        val kids = (0 until 200).mapNotNull { MaskLab.mutate(Masks.byId("chrome.l1")!!, Random(it)) }
        assertTrue(kids.any { it.mss > 0 }); assertTrue(kids.any { it.mux })
        assertTrue(kids.none { it.mss > 0 && it.viaByeDpi })
    }

    @Test fun dnsChain() {
        val c = JSONObject(XrayConfigBuilder.vpnConfig(listOf(ca to null), Settings(), null))
        val dns = c.getJSONObject("dns")
        val servers = dns.getJSONArray("servers").toString()
        listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://9.9.9.9/dns-query", "\"8.8.8.8\"").forEach { assertTrue(it, servers.contains(it.replace("/", "\\/")) || servers.contains(it)) }
        assertEquals("8.8.8.8", dns.getJSONObject("hosts").getString("dns.google"))
        assertTrue(c.getJSONArray("outbounds").toString().contains("nonIPQuery"))
    }
}
