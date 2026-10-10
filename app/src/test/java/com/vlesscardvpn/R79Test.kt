package com.vlesscardvpn

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class R79Test {
    @Test fun balancerNoticesDeadServerFaster() {
        val a = Server("a", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com")
        val b = a.copy(name = "b", address = "5.6.7.8")
        val cfg = JSONObject(XrayConfigBuilder.vpnConfig(listOf(a to null, b to null), Settings(), null))
        assertEquals("10s", cfg.getJSONObject("observatory").getString("probeInterval"))
        java.io.File(System.getenv("XRAY_CONFIG_DUMP") ?: return).apply { mkdirs() }.resolve("two.json").writeText(cfg.toString())
    }
}
