package com.vlesscardvpn.xray

import com.vlesscardvpn.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.45: turbo masks (zero cost, speed-first) and new QUIC-noise presets (z15/z16). */
class MasksTurboR158Test {
    private val tls = Server("a", "vless", "vc.example.com", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "vc.example.com")
    private val wg = Server("wg", "wireguard", "1.2.3.4", 51820, "k", pbk = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", source = "warp")

    @Test
    fun turboMasksAreZeroCostAndCompatible() {
        val ids = listOf("vc.turbo.sni.tfo", "vc.turbo.v6.tfo", "vc.turbo.sni.v6", "vc.turbo.mux.v6")
        for (id in ids) {
            val m = Masks.byId(id)
            assertTrue(id, m != null)
            assertEquals(id, 0, Masks.cost(m!!))
        }
        // turbo leads the curated order, right after the plain baseline (SNI/TFO variants need a pinned server)
        val first = Masks.searchOrder(true, tls).take(6).map { it.id }
        assertTrue("turbo mask in top-6: $first", "vc.turbo.v6.tfo" in first || "vc.turbo.mux.v6" in first)
    }

    @Test
    fun newQuicNoisesParseAsJson() {
        for (id in listOf("z15", "z16")) {
            val json = Masks.noiseItems(id)
            assertTrue(id, json != null)
            org.json.JSONArray(json!!)
        }
        // hop combos cover the new presets too
        val hopIds = Masks.ALL.map { it.id }.toSet()
        assertTrue(hopIds.contains("chrome.z15.hw"))
        assertTrue(hopIds.contains("chrome.z16.hl"))
        assertTrue(Masks.compatible(Masks.byId("chrome.z15.hw")!!, wg))
    }
}