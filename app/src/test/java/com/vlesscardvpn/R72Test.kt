package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R72Test {
    @Test fun stepwiseMigrationKeepsChoices() {
        // a 1.0.71 user who switched QUIC blocking off: speed-up and ad blocking come, QUIC stays as chosen
        val s71 = Settings.fromJson(Settings().copy(rev = 71, blockQuic = false, blockAds = false, turbo = false).toJson())
        val m = Settings.migrate(s71)
        assertFalse(m.blockQuic); assertTrue(m.turbo && m.blockAds); assertEquals(72, m.rev)
        assertFalse(Settings.migrate(m.copy(turbo = false)).turbo)
        val fresh = Settings.migrate(Settings.fromJson(JSONObject()))
        assertTrue(fresh.blockQuic && fresh.turbo && fresh.blockAds && !fresh.autoStart)
        assertTrue(Settings.fromJson(Settings(autoStart = true).toJson()).autoStart)
    }

    @Test fun turboDns() {
        val s = Server("c", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        val on = JSONObject(XrayConfigBuilder.vpnConfig(listOf(s to null), Settings(), null))
        assertTrue(on.getJSONObject("dns").getBoolean("serveStale"))
        assertTrue(on.getJSONObject("routing").toString().contains("category-ads-all"))
        val off = JSONObject(XrayConfigBuilder.vpnConfig(listOf(s to null), Settings(turbo = false), null))
        assertFalse(off.getJSONObject("dns").has("serveStale"))
        java.io.File(System.getenv("XRAY_CONFIG_DUMP") ?: return).apply { mkdirs() }.resolve("turbo.json").writeText(on.toString())
    }

    @Test fun bytesText() {
        assertEquals("512 КБ", Actions.bytes(512L * 1024)); assertEquals("35 МБ", Actions.bytes(35L shl 20))
        assertTrue(Actions.bytes(3L shl 30).startsWith("3"))
    }
}
