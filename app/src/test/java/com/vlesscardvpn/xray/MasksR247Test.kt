package com.vlesscardvpn.xray

import com.vlesscardvpn.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.135: more masks — new Hello shapes, zero-cost signature combos, more white SNI and WireGuard noise. */
class MasksR247Test {
    private val pinned = Server("p", "vless", "vc.example.com", 443, "11111111-1111-1111-1111-111111111111", security = "tls",
        sni = "vc.example.com", pcs = "ab".repeat(32))
    private val plainTls = Server("t", "vless", "vc.example.com", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "vc.example.com")
    private val reality = Server("r", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "reality", sni = "www.microsoft.com", pbk = "k")
    private val warp = Server("wg", "wireguard", "1.2.3.4", 51820, "k", pbk = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", source = "warp")

    @Test fun newFragmentShapesExistForEveryFingerprintAndRoute() {
        for (shape in listOf("hello.split", "hello.wave", "l8", "p8")) for (fp in Masks.FINGERPRINTS) {
            assertNotNull("$fp.$shape", Masks.byId("$fp.$shape"))
            assertNotNull("$fp.$shape.b", Masks.byId("$fp.$shape.b"))
        }
        val split = Masks.byId("chrome.hello.split")!!
        assertEquals("tlshello", split.packets); assertEquals(2, split.lengths.split(',').size); assertEquals(0, Masks.cost(split))
        assertEquals(1, Masks.cost(Masks.byId("chrome.p8")!!))
        assertTrue(Masks.compatible(split, reality))
        assertTrue(Masks.compatible(Masks.byId("chrome.p8")!!, plainTls))
    }

    @Test fun signatureCombosAreLightAndOfferedOnlyWhereTheyFit() {
        val ids = listOf("vc.tfo.fast", "vc.v6.fast", "vc.alpn.br.tfo", "vc.alpn.11.tfo", "vc.turbo.alpn.v6", "vc.turbo.sni.ya.tfo")
        for (id in ids) { val m = Masks.byId(id); assertNotNull(id, m); assertTrue(id, m!!.signature); assertEquals(id, 0, Masks.cost(m)) }
        assertEquals(1, Masks.cost(Masks.byId("vc.mux.tfo")!!))
        assertEquals(1, Masks.cost(Masks.byId("vc.mss536.fast")!!))
        for (d in Masks.EXTRA_SNI) {
            assertTrue(d in Masks.WHITE_POOL)
            val m = Masks.byId("vc.sni.$d")!!; val fast = Masks.byId("vc.sni.$d.fast")!!
            assertTrue(Masks.compatible(m, pinned)); assertTrue(Masks.compatible(fast, pinned))
            // a free SNI needs a certificate-pinned TLS server
            assertFalse(Masks.compatible(m, plainTls)); assertFalse(Masks.compatible(m, reality))
        }
        assertFalse(Masks.compatible(Masks.byId("vc.alpn.br.tfo")!!, reality))
        assertFalse(Masks.compatible(Masks.byId("vc.v6.fast")!!, reality)) // IP literal: no IPv6 path to prefer
        assertTrue(Masks.compatible(Masks.byId("vc.v6.fast")!!, plainTls))
    }

    @Test fun newWireGuardNoisesParseAndHop() {
        for (id in listOf("z17", "z18", "z19")) {
            org.json.JSONArray(Masks.noiseItems(id)!!)
            assertTrue(Masks.compatible(Masks.byId("chrome.$id")!!, warp))
        }
        for (id in listOf("chrome.z17.hl", "chrome.z17.hw", "chrome.z18.hl", "chrome.z18.hw")) assertTrue(id, Masks.compatible(Masks.byId(id)!!, warp))
        assertFalse(Masks.searchOrder(true, plainTls).any { it.noise in setOf("z17", "z18", "z19") })
    }

    @Test fun newMasksAreSearchedAfterTheProvenOrderAndTurboStaysOnTop() {
        val order = Masks.searchOrder(true, pinned).map { it.id }
        assertTrue(order.containsAll(listOf("vc.tfo.fast", "vc.sni.mail.ru.fast", "chrome.hello.split")))
        assertTrue(order.indexOf("vc.turbo.sni.tfo") < order.indexOf("vc.turbo.sni.ya.tfo"))
        val top = Masks.searchOrder(true, plainTls).take(6).map { it.id }
        assertTrue(top.toString(), "vc.turbo.v6.tfo" in top || "vc.turbo.mux.v6" in top)
        assertEquals(Masks.ALL.size, Masks.ALL.map { it.id }.toSet().size)
    }
}
