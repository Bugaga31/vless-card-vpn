package com.vlesscardvpn.xray

import com.vlesscardvpn.model.Base64
import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Mux parity: a probe runs the outbound exactly like the live tunnel; plain SOCKS servers never get Mux.Cool. */
class MuxProbeParityTest {
    private val ws = LinkParser.parse("vless://uuid-ws@cdn.example.com:443?security=tls&type=ws&host=cdn.example.com&path=%2Fws&sni=cdn.example.com#WS")!!
    private val socks = LinkParser.parse("socks://" + Base64.encode("user:pass") + "@5.5.5.5:1080#S")!!
    private fun outs(json: String) = JSONObject(json).getJSONArray("outbounds").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun probeUsesGlobalMuxLikeTheTunnel() {
        val probe = outs(XrayConfigBuilder.testConfig(listOf(ws to null), listOf(20001), null, mux = true)).first { it.getString("tag") == "v0" }
        val live = outs(XrayConfigBuilder.vpnConfig(listOf(ws to null), Settings(mux = true), 1080)).first { it.has("mux") }
        assertEquals(live.getJSONObject("mux").toString(), probe.getJSONObject("mux").toString())
    }

    @Test fun probeWithoutGlobalMuxStaysPlain() {
        val probe = outs(XrayConfigBuilder.testConfig(listOf(ws to null), listOf(20001), null)).first { it.getString("tag") == "v0" }
        assertFalse(probe.has("mux"))
        assertFalse(outs(XrayConfigBuilder.vpnConfig(listOf(ws to null), Settings(mux = false), 1080)).any { it.has("mux") })
    }

    @Test fun socksServerNeverGetsMux() {
        assertFalse(XrayConfigBuilder.muxable(socks))
        assertFalse(XrayConfigBuilder.outbound(socks, "s", Masks.byId("vc.mux"), true).has("mux"))
        assertFalse(Masks.compatible(Masks.byId("vc.mux")!!, socks))
        assertTrue(XrayConfigBuilder.muxable(ws))
    }

    @Test fun testerPassesTheSettingToEveryProbeConfig() {
        val p = "src/main/java/com/vlesscardvpn/core/Tester.kt"
        val s = listOf(File(p), File("app/$p")).first { it.isFile }.readText()
        assertTrue(s.contains("Store.state.value.settings.mux"))
        assertEquals(0, Regex("testConfig\\([^\\n]*dpiPorts\\)").findAll(s).count())
        assertEquals(3, Regex("testConfig\\([^\\n]*probeMux\\(\\)\\)").findAll(s).count())
    }
}
