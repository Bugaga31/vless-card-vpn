package com.vlesscardvpn

import com.vlesscardvpn.core.WarpKeys
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.MaskLab
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.MyMasks
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R66Test {
    private val tls = Server("a", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
    private val wg = Server("wg", "wireguard", "1.2.3.4", 51820, "k", pbk = "p")

    @After fun reset() { Masks.custom = emptyList(); Masks.auto = emptyList() }

    @Test fun myMaskLinkRoundTrip() {
        val m = MyMasks.fork(Masks.byId("firefox.l2")!!, "Шредер для Билайна")
        assertTrue(m.custom && m.id.startsWith("my:") && m.fingerprint == "firefox" && m.lengths == Masks.byId("firefox.l2")!!.lengths)
        val link = MyMasks.encode(m)
        assertTrue(link.startsWith("vcmask://") && ' ' !in link)
        assertEquals(m, MyMasks.decode(link))
        // several links inside a chat message; duplicates and garbage are dropped
        val text = "держи $link и ещё $link, а это мусор vcmask://!!!"
        assertEquals(listOf(m.id), MyMasks.parseLinks(text).map { it.id })
        // the id does not depend on the title: renaming keeps statistics
        assertEquals(m.id, MyMasks.fork(m, "Другое имя").id)
        assertEquals(listOf(m), MyMasks.load(listOf(MyMasks.store(m), "{broken")))
    }

    @Test fun myMaskValidation() {
        fun err(o: JSONObject) = runCatching { MyMasks.fromJson(o) }.exceptionOrNull()?.message
        assertTrue(err(JSONObject().put("fp", "netscape"))!!.contains("отпечаток"))
        assertTrue(err(JSONObject().put("p", "tlshello").put("ls", "1-1,2-4").put("ds", "1-2"))!!.contains("поровну"))
        assertTrue(err(JSONObject().put("p", "tlshello").put("l", "abc").put("d", "1-2")) != null)
        assertTrue(err(JSONObject().put("nj", "[{\"evil\":1}]")) != null)
        assertTrue(err(JSONObject().put("p", "tlshello").put("l", "1-3").put("d", "1-3").put("n", "z5"))!!.contains("разных"))
        assertNull(err(JSONObject().put("nj", "[{\"rand\":\"40-70\",\"delay\":\"1-3\"}]").put("h", Masks.HOP_LOCAL)))
    }

    @Test fun myMasksInSearchAndConfig() {
        val frag = MyMasks.fromJson(JSONObject().put("t", "моя").put("fp", "safari").put("p", "tlshello").put("l", "5-9").put("d", "2-4"))
        val noise = MyMasks.fromJson(JSONObject().put("t", "мой шум").put("nj", "[{\"rand\":\"33-44\",\"delay\":\"1-2\"}]"))
        Masks.custom = listOf(frag, noise)
        assertEquals(frag, Masks.byId(frag.id))
        assertEquals("my", Masks.family(frag))
        val forTls = Masks.searchOrder(true, tls)
        assertEquals(listOf("chrome.n", frag.id), forTls.take(2).map { it.id })
        assertFalse(forTls.contains(noise))
        // own masks are tried even when the family filter excludes everything else
        assertTrue(Masks.searchOrder(true, tls, listOf("zapret"), listOf("chrome")).contains(frag))
        val forWg = Masks.searchOrder(true, wg)
        assertTrue(forWg.contains(noise) && !forWg.contains(frag))
        val fm = XrayConfigBuilder.outbound(wg, "w", noise).getJSONObject("streamSettings").getJSONObject("finalmask")
        assertTrue(fm.toString().contains("33-44"))
        val tf = XrayConfigBuilder.outbound(tls, "t", frag).getJSONObject("streamSettings").getJSONObject("finalmask")
        assertTrue(tf.toString().contains("5-9"))
    }

    @Test fun newBuiltInMasks() {
        listOf("chrome.l4", "safari.l5", "firefox.l6", "chrome.p6", "chrome.z10", "chrome.z11", "chrome.z10.hw", "chrome.z11.hl",
            "chrome.h7", "safari.p7", "firefox.l7.b", "chrome.z12", "chrome.z12.hw").forEach {
            assertTrue(it, Masks.byId(it) != null)
        }
        Masks.ALL.filter { it.lengths.isNotEmpty() }.forEach { assertEquals(it.id, it.lengths.split(',').size, it.delays.split(',').size) }
        Masks.ALL.forEach { m -> Masks.noiseFor(m)?.let { assertTrue(m.id, org.json.JSONArray(it).length() > 0) } }
    }

    @Test fun warpKeyOrderAndStatus() {
        val now = 1_000_000_000_000L
        val a = WarpKeys.BUILT_IN[0]; val b = WarpKeys.BUILT_IN[1]; val c = WarpKeys.BUILT_IN[2]; val d = WarpKeys.BUILT_IN[3]
        val own = "AAAAAAAA-BBBBBBBB-CCCCCCCC"
        val stored = mapOf(a to WarpKeys.mark(WarpKeys.FULL, now - 1000), b to WarpKeys.mark(WarpKeys.OK, now), c to WarpKeys.mark(WarpKeys.BAD, now),
            d to WarpKeys.mark(WarpKeys.FULL, now - 10 * 24 * 3600_000L))
        val order = WarpKeys.order(listOf(own), true, off = listOf(WarpKeys.BUILT_IN[4]), stored = stored, now = now)
        assertEquals(own, order[0])
        assertEquals(b, order[1]) // known to work goes right after own keys
        assertEquals(a, order.last()) // used up recently: last
        assertFalse(c in order) // invalid: never tried
        assertFalse(WarpKeys.BUILT_IN[4] in order) // switched off
        assertTrue(d in order && order.indexOf(d) < order.indexOf(a)) // used up long ago: another chance
        assertEquals(WarpKeys.BUILT_IN.size - 2 + 1, order.size)
        assertEquals(listOf(own), WarpKeys.order(listOf(own), false, emptyList(), emptyMap()))
        assertEquals(WarpKeys.FULL, WarpKeys.classify("Too many connected devices."))
        assertEquals(WarpKeys.OK, WarpKeys.classify(null))
        assertNull(WarpKeys.classify("HTTP 503"))
        assertNull(WarpKeys.status(stored, d, now))
    }

    @Test fun settingsKeepNewFields() {
        val s = Settings(warpKeyOff = listOf("x"), warpKeyStatus = mapOf("k" to "ok@1"), myMasks = listOf("{\"t\":\"a\",\"fp\":\"chrome\"}"))
        val r = Settings.fromJson(s.toJson())
        assertEquals(s.warpKeyOff, r.warpKeyOff); assertEquals(s.warpKeyStatus, r.warpKeyStatus); assertEquals(s.myMasks, r.myMasks)
    }

    @Test fun maskEvolution() {
        val parents = listOf("firefox.l2", "chrome.h3", "chrome.z5", "chrome.z6.hw", "chrome.n").map { Masks.byId(it)!! }
        val pool = MaskLab.breed(parents, 40, known = emptySet(), seed = 42)
        assertEquals(40, pool.size)
        assertEquals(pool, MaskLab.breed(parents, 40, known = emptySet(), seed = 42)) // deterministic per seed
        pool.forEach { m ->
            assertTrue(m.id, m.auto && !m.custom && m.id.startsWith(MaskLab.PREFIX) && m.title.startsWith("Авто:"))
            assertEquals(m.id, Masks.family(m), "auto")
            if (m.lengths.isNotEmpty()) assertEquals(m.id, m.lengths.split(',').size, m.delays.split(',').size)
            // every mutant must build a valid outbound for some server type
            val s = if (m.hop == Masks.HOP_WARP) wg.copy(source = "warp") else if (m.hasNoise || m.hop.isNotEmpty()) wg else tls
            assertTrue(m.id, Masks.compatible(m, s))
            XrayConfigBuilder.outbound(s, "t", m).toString()
        }
        assertTrue(pool.any { it.hasNoise } && pool.any { it.packets.isNotEmpty() })
        // no parents: random newcomers; known ids are skipped
        val fresh = MaskLab.breed(emptyList(), 10, known = setOf(pool[0].id), seed = 1)
        assertEquals(10, fresh.size); assertFalse(fresh.any { it.id == pool[0].id })
        // round trip through Settings; a mutant of a mutant is the next generation
        val kept = MaskLab.keep(emptyList(), pool.take(3))
        assertEquals(pool.take(3), MaskLab.load(kept))
        assertTrue(MaskLab.keep(kept, pool).size <= MaskLab.CAP)
        val child = (1..20).mapNotNull { MaskLab.mutate(pool[0], kotlin.random.Random(it)) }.first()
        assertTrue(child.title, child.title.contains("поколение 2"))
        // auto masks resolve by id (servers point at them) and stay out of the plain search order
        Masks.auto = pool
        assertEquals(pool[5], Masks.byId(pool[5].id))
        assertFalse(Masks.searchOrder(true, tls).any { it.auto })
        val r = Settings.fromJson(Settings(autoMasks = kept, maskEvolution = false).toJson())
        assertEquals(kept, r.autoMasks); assertFalse(r.maskEvolution)
    }
}
