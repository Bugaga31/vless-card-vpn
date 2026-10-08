package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R78Test {
    private val s = Server("c", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")

    @Test fun smartYoutubeRoundTrip() {
        val a = Settings(smartYoutube = false, ytDpi = mapOf("wifi:Home" to true, "mobile:MTS" to false))
        val b = Settings.fromJson(JSONObject(a.toJson().toString()))
        assertFalse(b.smartYoutube); assertEquals(a.ytDpi, b.ytDpi)
        assertTrue(Settings.fromJson(JSONObject("{}")).smartYoutube)
    }

    @Test fun bypassMustWinClearly() {
        assertFalse(Actions.ytDpiWins(8000, 9000))   // a little faster is not worth it
        assertTrue(Actions.ytDpiWins(2000, 6000))
        assertTrue(Actions.ytDpiWins(0, 1500))        // servers can't open YouTube at all
        assertFalse(Actions.ytDpiWins(3000, 0))
    }

    @Test fun bufferOnlyWithTurbo() {
        fun lvl(t: Boolean) = JSONObject(XrayConfigBuilder.vpnConfig(listOf(s to null), Settings(turbo = t), null)).getJSONObject("policy").getJSONObject("levels").getJSONObject("0")
        assertEquals(64, lvl(true).getInt("bufferSize")); assertFalse(lvl(false).has("bufferSize"))
    }

    @Test fun youtubeViaBypassConfig() {
        val cfg = JSONObject(XrayConfigBuilder.vpnConfig(listOf(s to null), Settings(mode = Mode.HYBRID, hybridDomains = Settings.YT_DOMAINS, turbo = true), 10808))
        val r = cfg.getJSONObject("routing").toString()
        assertTrue(r.contains("googlevideo.com")); assertTrue(r.contains("geosite:youtube"))
        java.io.File(System.getenv("XRAY_CONFIG_DUMP") ?: return).apply { mkdirs() }.resolve("smart-yt.json").writeText(cfg.toString())
    }
}
