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

class R88Test {
    private val W = com.vlesscardvpn.core.AppWatch
    @org.junit.Test fun evolvesEveryTwoHoursWhenHealthy() {
        org.junit.Assert.assertEquals("evolve", W.pilot(0, false, false, 0, 0, 3 * 3600_000L))
        org.junit.Assert.assertNull(W.pilot(0, false, false, 0, 0, 3600_000L))
        org.junit.Assert.assertNull(W.pilot(1, false, false, 0, 0, 9 * 3600_000L))
        org.junit.Assert.assertNull(W.pilot(0, false, true, 0, 0, 9 * 3600_000L))
    }
}

class R89Test {
    @org.junit.Test fun traceCountry() {
        org.junit.Assert.assertEquals("NL", com.vlesscardvpn.core.Tester.traceLoc("fl=1\nip=1.2.3.4\nloc=nl\ntls=TLSv1.3"))
        org.junit.Assert.assertNull(com.vlesscardvpn.core.Tester.traceLoc("ip=1.2.3.4"))
    }
    @org.junit.Test fun geminiDomainsAlwaysThroughServers() {
        val st = com.vlesscardvpn.model.Settings(services = listOf("youtube"))
        org.junit.Assert.assertTrue(com.vlesscardvpn.core.AppWatch.AI_DOMAINS.contains("domain:gemini.google.com"))
        org.junit.Assert.assertTrue(com.vlesscardvpn.core.Services.domains(listOf("ai")).contains("domain:generativelanguage.googleapis.com"))
        org.junit.Assert.assertEquals(listOf("youtube"), st.services)
    }
}

class R90Test {
    private val W = com.vlesscardvpn.core.AppWatch
    @org.junit.Test fun slowTriggersSpeedup() {
        org.junit.Assert.assertEquals("speedup", W.pilot(0, false, false, 0, 0, 0, slow = true, sinceSpeedup = 2 * 3600_000L))
        org.junit.Assert.assertNull(W.pilot(0, false, false, 0, 0, 0, slow = true, sinceSpeedup = 60_000L))
    }
    @org.junit.Test fun remembersCountryPerApp() {
        val gem = W.PROFILES.getValue("com.google.android.apps.bard")
        org.junit.Assert.assertEquals("DE", W.decide(gem, listOf("RU"), listOf("US" to 1, "DE" to 1), remembered = "DE"))
        org.junit.Assert.assertEquals("US", W.decide(gem, listOf("RU"), listOf("US" to 1, "DE" to 1), remembered = "JP"))
    }
    @org.junit.Test fun widgetLook() {
        val on = com.vlesscardvpn.vpn.VpnWidget.look(com.vlesscardvpn.core.Tunnel.State.CONNECTED, true, "NL-1", "")
        org.junit.Assert.assertEquals("Подключено", on.second); org.junit.Assert.assertEquals("NL-1", on.third)
        org.junit.Assert.assertEquals("Подключено · нет интернета", com.vlesscardvpn.vpn.VpnWidget.look(com.vlesscardvpn.core.Tunnel.State.CONNECTED, false, "", "").second)
    }
}

class R91Test {
    private fun srv(link: String) = com.vlesscardvpn.model.LinkParser.parse(link)!!
    @org.junit.Test fun warpGoesThroughTheServer() {
        val vless = srv("vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?security=tls&type=tcp&sni=a.com#NL")
        val warp = srv("wireguard://cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MTI=@162.159.192.1:2408?publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D&address=172.16.0.2%2F32#WARP")
        val st = com.vlesscardvpn.model.Settings(mode = com.vlesscardvpn.model.Mode.SERVERS, warpChain = true)
        val c = org.json.JSONObject(com.vlesscardvpn.xray.XrayConfigBuilder.vpnConfig(listOf(vless to null), st, null, warpHop = warp))
        val outs = c.getJSONArray("outbounds"); var w: org.json.JSONObject? = null
        for (i in 0 until outs.length()) if (outs.getJSONObject(i).optString("tag") == "warp-chain") w = outs.getJSONObject(i)
        org.junit.Assert.assertNotNull(w)
        org.junit.Assert.assertEquals("proxy-0", w!!.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        val rules = c.getJSONObject("routing").getJSONArray("rules")
        org.junit.Assert.assertEquals("warp-chain", rules.getJSONObject(rules.length() - 1).optString("outboundTag"))
        // without the account: the plain config
        val plain = com.vlesscardvpn.xray.XrayConfigBuilder.vpnConfig(listOf(vless to null), st, null)
        org.junit.Assert.assertFalse(plain.contains("warp-chain"))
    }
}

class R92Test {
    @org.junit.Test fun dnsQueryWire() {
        val q = com.vlesscardvpn.core.Tester.dnsQuery("www.google.com", 0x1234)
        org.junit.Assert.assertEquals(0x12, q[0].toInt()); org.junit.Assert.assertEquals(0x34, q[1].toInt())
        org.junit.Assert.assertEquals(12 + 16 + 4, q.size)
        org.junit.Assert.assertEquals(3, q[12].toInt())
    }
    @org.junit.Test fun tunStall() {
        val T = com.vlesscardvpn.core.Tester
        org.junit.Assert.assertTrue(T.tunStalled(longArrayOf(0, 0, 0, 0), longArrayOf(250, 5000, 0, 0)))
        org.junit.Assert.assertFalse(T.tunStalled(longArrayOf(0, 0, 0, 0), longArrayOf(250, 5000, 40, 90000)))
        org.junit.Assert.assertFalse(T.tunStalled(longArrayOf(0, 0, 0, 0), longArrayOf(3, 100, 0, 0)))
        org.junit.Assert.assertFalse(T.tunStalled(null, longArrayOf(50, 0, 0, 0)))
    }
    @org.junit.Test fun dnsThroughRealXray() {
        val p = System.getenv("XRAY_SOCKS")?.toIntOrNull() ?: return
        org.junit.Assert.assertTrue(com.vlesscardvpn.core.Tester.dnsViaSocks(p))
        org.junit.Assert.assertTrue(com.vlesscardvpn.core.Tester.dnsViaSocks(p + 1, "u", "p"))
        org.junit.Assert.assertFalse(com.vlesscardvpn.core.Tester.dnsViaSocks(p + 2))
    }
}

class R93Test {
    private val T = com.vlesscardvpn.core.Thermal
    @org.junit.Test fun hotWithHysteresis() {
        org.junit.Assert.assertFalse(T.hot(39.0, 0, false))
        org.junit.Assert.assertTrue(T.hot(41.5, 0, false))
        org.junit.Assert.assertTrue(T.hot(30.0, 2, false))
        org.junit.Assert.assertTrue(T.hot(39.0, 1, true))
        org.junit.Assert.assertFalse(T.hot(37.0, 1, true))
    }
    @org.junit.Test fun label() {
        org.junit.Assert.assertEquals("42 °C · горячий", T.label(42.0, 2))
        org.junit.Assert.assertEquals("", T.label(0.0, -1))
    }
}

class R94Test {
    private val a = com.vlesscardvpn.core.DpiEvo.decode(com.vlesscardvpn.core.DpiEvo.encode(listOf("--split", "1+s", "--disorder", "1", "--fake", "-1", "--ttl", "8")))!!
    private val b = com.vlesscardvpn.core.DpiEvo.decode(com.vlesscardvpn.core.DpiEvo.encode(listOf("--oob", "1", "--tlsrec", "3+s")))!!
    @org.junit.Test fun genes() {
        val g = com.vlesscardvpn.core.DpiEvo.genes(a.args)!!
        org.junit.Assert.assertEquals(listOf(listOf("--split", "1+s"), listOf("--disorder", "1"), listOf("--fake", "-1", "--ttl", "8")), g)
        org.junit.Assert.assertNull(com.vlesscardvpn.core.DpiEvo.genes(listOf("--split", "1", "--auto", "torst", "--disorder", "1")))
    }
    @org.junit.Test fun breedReshapesAndCrosses() {
        val kids = com.vlesscardvpn.core.DpiEvo.breed(listOf(a, b), 30, seed = 7)
        org.junit.Assert.assertTrue(kids.size >= 15)
        kids.forEach { k ->
            org.junit.Assert.assertTrue(k.id, k.id.startsWith("EVO#") && k.args != a.args && k.args != b.args)
            org.junit.Assert.assertEquals(k.args, com.vlesscardvpn.core.DpiEvo.decode(k.id)!!.args)
            org.junit.Assert.assertTrue(com.vlesscardvpn.core.ByeDpiArgs.parse(k.args.joinToString(" ")).isSuccess)
        }
        // shapes change, not only numbers: some child has an option set neither parent alone had
        org.junit.Assert.assertTrue(kids.any { k -> val f = k.args.filter { it.startsWith("--") }.toSet(); f != a.args.filter { it.startsWith("--") }.toSet() && f != b.args.filter { it.startsWith("--") }.toSet() })
        org.junit.Assert.assertEquals(kids, com.vlesscardvpn.core.DpiEvo.breed(listOf(a, b), 30, seed = 7))
    }
    @org.junit.Test fun poolKeepAndPlan() {
        val p = com.vlesscardvpn.core.DpiEvo.keep(listOf("EVO#x"), listOf(a.id, "BYEDPI#1", a.id))
        org.junit.Assert.assertEquals(listOf(a.id, "EVO#x"), p)
        org.junit.Assert.assertEquals(20, com.vlesscardvpn.core.DpiEvo.keep((1..30).map { "EVO#$it" }, emptyList()).size)
        val s = com.vlesscardvpn.model.Settings(dpiEvoPool = listOf(b.id))
        org.junit.Assert.assertTrue(com.vlesscardvpn.core.DpiStrategies.plan(s, "wifi:x", false).any { it.id == b.id })
        val back = com.vlesscardvpn.model.Settings.fromJson(org.json.JSONObject(s.toJson().toString()))
        org.junit.Assert.assertEquals(listOf(b.id), back.dpiEvoPool)
    }
    @org.junit.Test fun maskCrossAndSelection() {
        val rnd = kotlin.random.Random(3)
        val ps = (1..6).mapNotNull { com.vlesscardvpn.xray.MaskLab.random(rnd, udp = false) }
        val kids = com.vlesscardvpn.xray.MaskLab.breed(ps, 40, known = emptySet(), seed = 11)
        org.junit.Assert.assertTrue(kids.any { it.title.contains("скрещивание") })
        val c = (0 until 50).firstNotNullOf { com.vlesscardvpn.xray.MaskLab.cross(ps[0], ps[1], kotlin.random.Random(it)) }
        org.junit.Assert.assertTrue(c.auto && c.id.startsWith("auto:"))
        val stored = ps.map { com.vlesscardvpn.xray.MaskLab.store(it) }
        val stats = mapOf("n1" to mapOf(ps[0].id to com.vlesscardvpn.core.MaskStat(0, 4)), "n2" to mapOf(ps[0].id to com.vlesscardvpn.core.MaskStat(0, 3)))
        val kept = com.vlesscardvpn.xray.MaskLab.load(com.vlesscardvpn.xray.MaskLab.keep(stored, emptyList(), stats)).map { it.id }
        org.junit.Assert.assertFalse(ps[0].id in kept); org.junit.Assert.assertTrue(ps[1].id in kept)
        // a winner is never pruned
        org.junit.Assert.assertTrue(ps[0].id in com.vlesscardvpn.xray.MaskLab.load(com.vlesscardvpn.xray.MaskLab.keep(stored, listOf(ps[0]), stats)).map { it.id })
    }
}

class R95Test {
    private val W = com.vlesscardvpn.core.Whitelist
    @org.junit.Test fun verdicts() {
        org.junit.Assert.assertEquals(com.vlesscardvpn.core.Whitelist.Verdict.WHITELIST, W.verdict(4, 0, 0))
        org.junit.Assert.assertEquals(com.vlesscardvpn.core.Whitelist.Verdict.WHITELIST, W.verdict(3, 1, 0))
        org.junit.Assert.assertEquals(com.vlesscardvpn.core.Whitelist.Verdict.NORMAL, W.verdict(4, 4, 0))
        org.junit.Assert.assertEquals(com.vlesscardvpn.core.Whitelist.Verdict.OPEN, W.verdict(4, 4, 3))
        org.junit.Assert.assertEquals(com.vlesscardvpn.core.Whitelist.Verdict.NONE, W.verdict(0, 0, 0))
        org.junit.Assert.assertTrue(W.report(com.vlesscardvpn.core.Whitelist.Result(4, 0, 0, com.vlesscardvpn.core.Whitelist.Verdict.WHITELIST)).contains("SNI"))
    }
}

class R96Test {
    private val D = com.vlesscardvpn.core.Diagnose
    @org.junit.Test fun causes() {
        val H = com.vlesscardvpn.core.Diagnose.Hs.values().associateBy { it.name }
        val C = com.vlesscardvpn.core.Diagnose.Cause.values().associateBy { it.name }
        org.junit.Assert.assertEquals(C["DNS"], D.classify(false, false, null, null))
        org.junit.Assert.assertEquals(C["IP"], D.classify(true, false, null, null))
        org.junit.Assert.assertEquals(C["OK"], D.classify(true, true, H["ANSWER"], null))
        org.junit.Assert.assertEquals(C["SNI"], D.classify(true, true, H["TIMEOUT"], H["OK"]))
        org.junit.Assert.assertEquals(C["TLS"], D.classify(true, true, H["RESET"], H["TIMEOUT"]))
    }
    @org.junit.Test fun freeze() {
        val F = com.vlesscardvpn.core.Diagnose.Freeze.values().associateBy { it.name }
        org.junit.Assert.assertEquals(F["FROZEN"], D.freezeOf(16_384, 256_000, true))
        org.junit.Assert.assertEquals(F["OK"], D.freezeOf(256_000, 256_000, false))
        org.junit.Assert.assertEquals(F["FAIL"], D.freezeOf(0, 256_000, true))
        org.junit.Assert.assertEquals(F["OK"], D.freezeOf(200_000, 256_000, true))
    }
}
