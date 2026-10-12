package com.vlesscardvpn

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R128Test {
    private fun srv(i: Int) = Server(name = "s$i", protocol = "vless", address = "10.0.0.$i", port = 443, secret = "11111111-1111-1111-1111-11111111111$i",
        security = "tls", sni = "a$i.com")

    @Test fun heavyPick() {
        assertEquals(setOf(1), XrayConfigBuilder.heavyOf(listOf(5000, 20000, 9000), listOf(true, true, true)))
        assertEquals(setOf(1, 2), XrayConfigBuilder.heavyOf(listOf(5000, 20000, 15000), listOf(true, true, null)))
        assertEquals(setOf(2), XrayConfigBuilder.heavyOf(listOf(5000, 20000, 15000), listOf(true, false, true)))  // YouTube failed there
        assertEquals(emptySet<Int>(), XrayConfigBuilder.heavyOf(listOf(0, 0), listOf(true, true)))
    }

    @Test fun heavyBalancerInConfig() {
        val st = Settings(mode = Mode.SERVERS)
        val cfg = XrayConfigBuilder.vpnConfig(listOf(srv(1) to null, srv(2) to null, srv(3) to null), st, null, heavy = setOf(1))
        val o = JSONObject(cfg); val r = o.getJSONObject("routing")
        val bals = r.getJSONArray("balancers")
        assertEquals(2, bals.length())
        assertEquals("heavy", bals.getJSONObject(1).getString("tag"))
        assertEquals("proxy-1", bals.getJSONObject(1).getJSONArray("selector").getString(0))
        val rules = r.getJSONArray("rules"); val heavy = (0 until rules.length()).map { rules.getJSONObject(it) }.first { it.optString("balancerTag") == "heavy" }
        assertTrue(heavy.getJSONArray("domain").toString().contains("googlevideo"))
        // services-only without YouTube: no heavy route (YouTube goes direct there)
        val only = JSONObject(XrayConfigBuilder.vpnConfig(listOf(srv(1) to null, srv(2) to null), st.copy(services = listOf("telegram")), null, heavy = setOf(1)))
        assertEquals(1, only.getJSONObject("routing").getJSONArray("balancers").length())
        // one server: nothing to split
        val one = JSONObject(XrayConfigBuilder.vpnConfig(listOf(srv(1) to null), st, null, heavy = setOf(0)))
        assertNull(one.getJSONObject("routing").optJSONArray("balancers"))
        System.getenv("XRAY_CONFIG_DUMP")?.let { d -> java.io.File(d).mkdirs(); java.io.File(d, "heavy.json").writeText(cfg) }
    }
}
