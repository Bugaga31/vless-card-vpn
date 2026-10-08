package com.vlesscardvpn

import com.vlesscardvpn.core.AppState
import com.vlesscardvpn.core.DpiEvo
import com.vlesscardvpn.core.DpiStrategies
import com.vlesscardvpn.core.MaskStat
import com.vlesscardvpn.core.Net
import com.vlesscardvpn.core.NetProfile
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.MaskLab
import com.vlesscardvpn.xray.Masks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class R69Test {
    @After fun reset() { Masks.custom = emptyList(); Masks.auto = emptyList() }

    @Test fun wifiFamilyFallback() {
        assertEquals("Wi-Fi", Net.family("Wi-Fi · 192.168.1.1"))
        assertEquals("Моб.: МТС", Net.family("Моб.: МТС"))
        // learned before 1.0.69 under plain "Wi-Fi" → still used on a named Wi-Fi
        val s = Settings(dpiRemembered = mapOf("Wi-Fi" to DpiStrategies.CASCADE.id), dpiRanking = mapOf("Wi-Fi" to listOf(DpiStrategies.CASCADE.id)))
        assertEquals(DpiStrategies.CASCADE.id, DpiStrategies.resolve(s, "Wi-Fi · 10.0.0.1").id)
        assertTrue(DpiStrategies.plan(s, "Wi-Fi · 10.0.0.1", false).take(2).any { it.id == DpiStrategies.CASCADE.id })
        val st = ServerState(maskId = "a", netMasks = mapOf("Wi-Fi" to "b"))
        assertEquals("b", st.maskFor("Wi-Fi · 10.0.0.1"))
        assertEquals("a", st.maskFor("Моб.: МТС"))
    }

    @Test fun netProfileRoundTrip() {
        val evo = DpiEvo.breed(listOf(DpiStrategies.CASCADE), 1, seed = 5).first()
        val auto = MaskLab.mutate(Masks.byId("firefox.l2")!!, Random(3))!!.let { MaskLab.make(com.vlesscardvpn.xray.MyMasks.toJson(it)) }
        Masks.auto = listOf(auto)
        val net = "Моб.: МТС"
        val a = AppState(settings = Settings(dpiRemembered = mapOf(net to evo.id), dpiRanking = mapOf(net to listOf(evo.id, DpiStrategies.CASCADE.id, "custom")),
            autoMasks = listOf(MaskLab.store(auto))),
            maskStats = mapOf(net to mapOf("firefox.l2" to MaskStat(3, 0), auto.id to MaskStat(1, 0), "chrome" to MaskStat(0, 5))))
        val p = NetProfile.build(a, net)
        assertEquals(listOf(evo.id, DpiStrategies.CASCADE.id), p.dpi)
        assertEquals(listOf("firefox.l2"), p.masks)
        assertEquals(1, p.extra.size)
        val link = NetProfile.encode(p)
        assertTrue(link.startsWith("vcnet://"))
        val back = NetProfile.parseLinks("лови: $link спасибо").single()
        assertEquals(p.net, back.net); assertEquals(p.dpi, back.dpi); assertEquals(p.masks, back.masks)

        // a friend with a fresh app on the same provider
        Masks.auto = emptyList()
        val (b, applied) = NetProfile.apply(AppState(), back, "Моб.: МТС")
        assertEquals(2, applied.dpi); assertEquals(2, applied.masks)
        assertEquals(evo.id, DpiStrategies.resolve(b.settings, "Моб.: МТС").id)
        assertEquals(auto.id, MaskLab.load(b.settings.autoMasks).single().id)
        assertTrue(b.maskStats.getValue("Моб.: МТС").getValue("firefox.l2").ok > 0)
        assertTrue(NetProfile.parseLinks("vcnet://!!!").isEmpty())
    }

    @Test fun disguiseLayers() {
        val s = Settings.fromJson(Settings(ruAppsDirect = false, coverTraffic = false).toJson())
        assertTrue(!s.ruAppsDirect && !s.coverTraffic && s.randomTun)
        assertTrue(Settings.fromJson(org.json.JSONObject()).let { it.ruAppsDirect && it.randomTun && it.coverTraffic })
        repeat(50) {
            val v4 = com.vlesscardvpn.core.Disguise.tunV4().split('.').map { it.toInt() }
            assertEquals(10, v4[0]); assertTrue(v4[1] in 1..254 && v4[3] % 4 == 1)
            assertTrue(com.vlesscardvpn.core.Disguise.tunV6().matches(Regex("^fd[0-9a-f]{2}:[0-9a-f]{4}:[0-9a-f]{4}::1$")))
        }
        assertTrue(com.vlesscardvpn.core.Disguise.nextDelaySec() in 40..180)
        assertEquals(com.vlesscardvpn.core.Disguise.RU_APPS.size, com.vlesscardvpn.core.Disguise.RU_APPS.toSet().size)
    }
}
