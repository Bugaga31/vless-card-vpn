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
