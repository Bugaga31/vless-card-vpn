package com.vlesscardvpn

import com.vlesscardvpn.core.Assistant
import com.vlesscardvpn.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R84Test {
    private val f = com.vlesscardvpn.core.Assistant.Facts("Wi-Fi", emptyMap(), true, "r", "ok", 3, 2, emptyList(), Mode.AUTO, null, false, null,
        com.vlesscardvpn.core.PhoneInfo("Xiaomi 13", "14", 34, 8, 8.0, 0.3, 20.0, 12, false, true, true, false, "hostname", "Wi-Fi", false, 50, 10))
    private fun cmd(q: String) = com.vlesscardvpn.core.Assistant.answer(q, f).let { if (it.auto) it.buttons.first().cmd else "" }

    @Test fun ordersAreExecuted() {
        assertEquals("!yt", cmd("ускорь ютуб пожалуйста"))
        assertEquals("!yt", cmd("видео тормозит, ускорь"))
        assertEquals("!nextmask", cmd("смени маску"))
        assertEquals("!auto", cmd("включи авто"))
        assertEquals("!set turbo off", cmd("выключи турбо"))
        assertEquals("!set blockAds on", cmd("блокируй рекламу, включи"))
        assertEquals("!disconnect", cmd("отключи впн"))
        assertEquals("", cmd("почему медленно"))
    }

    @Test fun knowsThePhone() {
        val a = com.vlesscardvpn.core.Assistant.answer("что с телефоном?", f)
        assertTrue(a.text, a.text.contains("Xiaomi 13") && a.text.contains("Оптимизация батареи") && a.text.contains("энергосбережения"))
        assertTrue(a.buttons.any { it.cmd == "!battery" })
        assertEquals(5, f.phone!!.problems().size)   // battery opt, power save, private DNS, low RAM, low battery
    }

    @Test fun pingsManyAtOnce() {
        val srv = java.net.ServerSocket(0, 1000)
        val dead = java.net.ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val targets = List(300) { i -> java.net.InetSocketAddress("127.0.0.1", if (i % 3 == 0) dead else srv.localPort) }
        val res = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val t0 = System.nanoTime()
        com.vlesscardvpn.core.Tester.nioPing(targets, 1500) { i, ms -> res[i] = ms }
        val took = (System.nanoTime() - t0) / 1_000_000
        srv.close()
        assertEquals(300, res.size)
        assertTrue(res.filterKeys { it % 3 != 0 }.values.all { it > 0 })
        assertTrue(res.filterKeys { it % 3 == 0 }.values.all { it == 0 })
        assertTrue("took $took ms", took < 3000)
    }
}

class R85Test {
    private fun f(stats: Map<String, com.vlesscardvpn.core.MaskStat>) = com.vlesscardvpn.core.Assistant.Facts("Моб.: MTS", stats, true, "r", "ok", 3, 2, emptyList(), Mode.AUTO, null, false, null)

    @Test fun explainsFromStatistics() {
        val all = com.vlesscardvpn.xray.Masks.ALL
        val frag = all.filter { it.packets.isNotEmpty() && !it.mux && it.dpi.isEmpty() }.take(6)
        val mux = all.filter { it.mux }.take(3)
        val plain = all.filter { it.packets.isEmpty() && !it.mux && it.dpi.isEmpty() && it.sni.isEmpty() && it.mss == 0 && it.lengths.isEmpty() && !it.tfo && !it.ipv6 && it.alpn != "http/1.1" && it.fingerprint !in setOf("firefox", "safari") }.take(3)
        val stats = frag.associate { it.id to com.vlesscardvpn.core.MaskStat(4, 0) } + mux.associate { it.id to com.vlesscardvpn.core.MaskStat(0, 4) } + plain.associate { it.id to com.vlesscardvpn.core.MaskStat(0, 4) }
        val e = com.vlesscardvpn.core.Assistant.effects(stats)
        assertTrue(e.toString(), e.first().first.contains("дробление") && e.first().second > 0.5)
        val a = com.vlesscardvpn.core.Assistant.answer("что режут в моей сети?", f(stats))
        assertTrue(a.text, a.text.contains("Помогает: дробление") && a.text.contains("явно режут"))
        assertTrue(com.vlesscardvpn.core.Assistant.answer("какие маски лучше", f(stats)).text.contains("прошла 4 из 4"))
        assertTrue(com.vlesscardvpn.core.Assistant.answer("что режут", f(emptyMap())).text.contains("мало знаю"))
    }
}

class R86Test {
    private val gem = com.vlesscardvpn.core.AppWatch.PROFILES.getValue("com.google.android.apps.bard")
    @org.junit.Test fun geminiLeavesRussia() {
        org.junit.Assert.assertEquals("NL", com.vlesscardvpn.core.AppWatch.decide(gem, listOf("RU"), listOf("RU" to 3, "NL" to 2, "TR" to 5)))
    }
    @org.junit.Test fun geminiKeepsGoodServers() {
        org.junit.Assert.assertNull(com.vlesscardvpn.core.AppWatch.decide(gem, listOf("DE", "FI"), listOf("US" to 1)))
    }
    @org.junit.Test fun unknownCountryMovesToKnown() {
        org.junit.Assert.assertEquals("US", com.vlesscardvpn.core.AppWatch.decide(gem, listOf(""), listOf("US" to 1, "DE" to 9)))
        org.junit.Assert.assertNull(com.vlesscardvpn.core.AppWatch.decide(gem, listOf("HK"), listOf("RU" to 2, "" to 4)))
    }
    @org.junit.Test fun youtubeNeedsNoCountry() {
        val yt = com.vlesscardvpn.core.AppWatch.PROFILES.getValue("com.google.android.youtube")
        org.junit.Assert.assertTrue(yt.youtube)
        org.junit.Assert.assertNull(com.vlesscardvpn.core.AppWatch.decide(yt, listOf("RU"), listOf("US" to 1)))
    }
    @org.junit.Test fun gpsHasEveryPreferredCountry() {
        com.vlesscardvpn.core.AppWatch.PREFER.forEach { org.junit.Assert.assertTrue(it, it in com.vlesscardvpn.core.GpsMock.COORDS) }
        org.junit.Assert.assertEquals("DE", com.vlesscardvpn.core.AppWatch.gpsCountry("", listOf("", "DE")))
        org.junit.Assert.assertEquals("US", com.vlesscardvpn.core.AppWatch.gpsCountry("US", listOf("DE")))
    }
}

class R87Test {
    private val W = com.vlesscardvpn.core.AppWatch
    @org.junit.Test fun pilotFixesAfterTwoFails() {
        org.junit.Assert.assertNull(W.pilot(1, false, false, 1_000_000, 1_000_000))
        org.junit.Assert.assertEquals("fix", W.pilot(2, false, false, 1_000_000, 1_000_000))
        org.junit.Assert.assertNull(W.pilot(3, false, false, 60_000, 1_000_000))
    }
    @org.junit.Test fun pilotLightensAndWaitsWhenBusy() {
        org.junit.Assert.assertEquals("lighten", W.pilot(0, true, false, 0, 4_000_000))
        org.junit.Assert.assertNull(W.pilot(5, true, true, 9_000_000, 9_000_000))
    }
}
