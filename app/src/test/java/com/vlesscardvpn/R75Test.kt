package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.Masks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R75Test {
    @Test fun scoreLikesSpeedNotOnlyPing() {
        val pingOnly = ServerState(realMs = 60, bigOk = true, kbps = 900)      // fast ping, 0.9 Mbit/s
        val fast = ServerState(realMs = 140, bigOk = true, kbps = 25_000, ytOk = true)
        assertTrue(fast.score < pingOnly.score)
        assertTrue(ServerState(realMs = 100, bigOk = true, ytOk = false, kbps = 20_000).score > ServerState(realMs = 300, bigOk = true, ytOk = true, kbps = 20_000).score)
        assertEquals(Int.MAX_VALUE, ServerState(realMs = 0).score)
        assertEquals(25_000, ServerState.fromJson(fast.toJson()).kbps)
        assertEquals("23 Мбит/с", Actions.mbps(23_400)); assertEquals("0.8 Мбит/с", Actions.mbps(800))
    }

    @Test fun lightMasksFirst() {
        val s = Server("c", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        val order = Masks.searchOrder(false, s)
        assertEquals("chrome.n", order.first().id)
        val firstHeavy = order.indexOfFirst { Masks.cost(it) >= 2 }; val lastLight = order.indexOfLast { Masks.cost(it) == 0 }
        assertTrue("heavy masks after all light ones", firstHeavy > lastLight)
        assertEquals(3, Masks.cost(Masks.byId("vc.mss120")!!)); assertEquals(0, Masks.cost(Masks.byId("chrome.z5")!!))
    }

    @Test fun diverseCoversEveryKind() {
        val s = Server("c", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        val all = Masks.searchOrder(true, s)
        val d = Masks.diverse(all)
        assertEquals(all.size, d.size); assertEquals(all.toSet(), d.toSet())
        val kinds = all.map { Masks.kind(it) }.distinct()
        assertEquals(kinds.toSet(), d.take(kinds.size).map { Masks.kind(it) }.toSet())
    }
}
