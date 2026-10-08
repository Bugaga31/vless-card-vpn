package com.vlesscardvpn

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.MyMasks
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R71Test {
    private val byName = Server("n", "vless", "srv.example.com", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "srv.example.com")
    private val byIp = byName.copy(address = "1.2.3.4")

    @Test fun migrationSwitchesMaskingOn() {
        val old = Settings.fromJson(JSONObject().put("blockQuic", false).put("rotateMasks", false).put("coverTraffic", false).put("maskFamilies", org.json.JSONArray(listOf("frag"))))
        assertEquals(0, old.rev)
        val m = Settings.migrate(old)
        assertTrue(m.blockQuic && m.rotateMasks && m.coverTraffic && m.stealthSocks && m.maskEvolution)
        assertTrue("brand" in m.maskFamilies && "auto" in m.maskFamilies)
        assertEquals(Settings.REV, m.rev)
        // later the user's own choice is kept
        val off = Settings.fromJson(m.copy(blockQuic = false).toJson())
        assertFalse(Settings.migrate(off).blockQuic)
        assertTrue(Settings().blockQuic && Settings().rotateMasks && Settings().rev == Settings.REV)
    }

    @Test fun moreSignatureMasks() {
        val v6 = Masks.byId("vc.ghost.v6")!!
        assertTrue(Masks.compatible(v6, byName)); assertFalse(Masks.compatible(v6, byIp))
        val sock = XrayConfigBuilder.outbound(byName, "p", v6).getJSONObject("streamSettings").getJSONObject("sockopt")
        assertEquals("UseIPv6v4", sock.getString("domainStrategy")); assertEquals(300, sock.getInt("tcpMaxSeg"))
        val tfo = XrayConfigBuilder.outbound(byIp, "p", Masks.byId("vc.ghost.tfo")!!).getJSONObject("streamSettings").getJSONObject("sockopt")
        assertTrue(tfo.getBoolean("tcpFastOpen"))
        assertTrue(Masks.ALL.count { it.signature } >= 33)
        val back = MyMasks.decode(MyMasks.encode(MyMasks.fork(Masks.byId("vc.ghost.tfo")!!)))
        assertTrue(back.tfo && back.mux && back.mss == 120)
        assertEquals("brand", Masks.family(Masks.byId("vc.mux.b")!!))
    }
}
