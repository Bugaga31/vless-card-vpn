package com.vlesscardvpn

import com.vlesscardvpn.core.Warp
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.SocksAuth
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R62Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun x25519Rfc7748() {
        // RFC 7748 §6.1: Alice's key pair
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(Warp.publicKey(priv)))
        // §5.2 vector 1
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            hex(Warp.x25519(hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"), hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"))))
    }

    @Test fun warpParseAndServers() {
        val o = JSONObject("""{"id":"x","config":{"client_id":"AQID","peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=","endpoint":{"host":"engage.cloudflareclient.com:2408"}}],
            "interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700::2"}}}}""")
        val a = Warp.parse(o, "priv=")
        assertEquals("1,2,3", a.reserved)
        val list = Warp.servers(a, Warp.ENDPOINTS.take(4))
        assertEquals(4, list.size); assertTrue(list.all { it.protocol == "wireguard" && it.source == Warp.SOURCE && it.localAddress == "172.16.0.2/32" })
        val out = XrayConfigBuilder.outbound(list[0], "p", Masks.byId("chrome.z1"))
        assertEquals(3, out.getJSONObject("settings").getJSONArray("reserved").length())
        // same key on 4 endpoints: never in one test batch
        val ch = com.vlesscardvpn.core.Tester.chunks(list.map { it to null }, 32)
        assertEquals(4, ch.size)
    }

    @Test fun proxyModeConfig() {
        val s = Server("a", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        val c = JSONObject(XrayConfigBuilder.vpnConfig(listOf(s to null), Settings(mode = com.vlesscardvpn.model.Mode.SERVERS, proxyOnly = true, lanShare = true),
            null, SocksAuth(10808), listen = "0.0.0.0", httpPort = 10809))
        val ins = c.getJSONArray("inbounds")
        assertEquals(2, ins.length())
        assertEquals("http", ins.getJSONObject(1).getString("protocol")); assertEquals(10809, ins.getJSONObject(1).getInt("port"))
        assertEquals("0.0.0.0", ins.getJSONObject(0).getString("listen"))
        val st = Settings.fromJson(Settings(proxyOnly = true, lanShare = true, perApp = false, maskFamilies = listOf("ladder"), maskFps = listOf("firefox")).toJson())
        assertTrue(st.proxyOnly && st.lanShare && !st.perApp && st.maskFamilies == listOf("ladder") && st.maskFps == listOf("firefox"))
    }

    @Test fun maskFamilies() {
        val s = Server("a", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        val all = Masks.searchOrder(true, s)
        val fams = all.map { Masks.family(it) }.toSet()
        assertTrue(fams.containsAll(listOf("plain", "frag", "ladder", "viadpi", "own", "zapret", "byedpi")))
        val ladders = Masks.searchOrder(true, s, listOf("ladder"), listOf("firefox"))
        assertTrue(ladders.first().id == "chrome.n")
        assertTrue(ladders.drop(1).isNotEmpty() && ladders.drop(1).all { Masks.family(it) == "ladder" && it.fingerprint == "firefox" })
        assertEquals("noise", Masks.family(Masks.byId("chrome.z1")!!))
    }

    @Test fun warpPlusKeys() {
        val post = "🔐 Key: <code>h51Zl4W3-0gX3Cd82-9ngP82u6</code> (1923837100 GB)🔐 Key: <code>65jSa4G2-WO7A520P-X5mF349N</code>"
        assertEquals(listOf("h51Zl4W3-0gX3Cd82-9ngP82u6", "65jSa4G2-WO7A520P-X5mF349N"), com.vlesscardvpn.core.WarpKeys.parse(post))
        assertEquals(135, com.vlesscardvpn.core.WarpKeys.BUILT_IN.size)
        assertTrue(com.vlesscardvpn.core.WarpKeys.BUILT_IN.all { com.vlesscardvpn.core.WarpKeys.parse(it) == listOf(it) })
        val s = Settings.fromJson(Settings(warpKeys = "abc", warpBuiltinKeys = false).toJson())
        assertTrue(s.warpKeys == "abc" && !s.warpBuiltinKeys)
    }

    @Test fun wgMasksAndWarpDump() {
        val warp = Warp.servers(Warp.Account("@WPRIV@", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", "@WV4@", "", "11,22,33", "x"), listOf("162.159.192.1:2408"))[0]
        val wg = Server("wg", "wireguard", "1.2.3.4", 51820, "k", pbk = "p")
        val wm = Masks.searchOrder(true, warp)
        assertTrue(wm.any { it.hop == Masks.HOP_WARP } && wm.any { it.noise == "z6" })
        assertTrue(Masks.searchOrder(true, wg).none { it.hop == Masks.HOP_WARP })
        val tls = Server("a", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        assertTrue(Masks.searchOrder(true, tls).none { it.hop.isNotEmpty() || it.noise.isNotEmpty() })
        val hy = Server("h", "hysteria2", "1.2.3.4", 443, "pw", sni = "a.com")
        assertTrue(Masks.searchOrder(true, hy).none { it.noise == "z5" || it.hop.isNotEmpty() })
        val o = XrayConfigBuilder.outbound(warp, "p", Masks.byId("chrome.z5.hw"))
        val udp = o.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("udp")
        assertEquals("noise", udp.getJSONObject(0).getString("type")); assertEquals("udphop", udp.getJSONObject(1).getString("type"))
        val dir = System.getenv("XRAY_CONFIG_DUMP") ?: return
        java.io.File(dir).mkdirs()
        val ids = wm.map { it.id }
        java.io.File(dir, "warp-masks.txt").writeText(ids.joinToString("\n"))
        ids.forEach { id ->
            val ob = XrayConfigBuilder.outbound(warp, "w", Masks.byId(id))
            val c = JSONObject().put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", org.json.JSONArray().put(JSONObject().put("listen", "127.0.0.1").put("port", 24901).put("protocol", "socks").put("settings", JSONObject().put("udp", true))))
                .put("outbounds", org.json.JSONArray().put(ob))
            java.io.File(dir, "warp-$id.json").writeText(c.toString())
        }
    }
}
