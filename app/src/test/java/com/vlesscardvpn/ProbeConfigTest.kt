package com.vlesscardvpn

import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeConfigTest {
    private fun node() = VlessConfig(name = "TLS", address = "vpn.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", sni = "server.example.org", flow = "")
    @Test fun probeIsLoopbackOnlyAuthenticatedAndForcedToProxyBeforeAnyBypass() {
        val proxy = LocalProbeProxy(10810, "probe", "session-password")
        val root = JSONObject(SingBoxManager.generateConfig(null, node(), AppSettings(), probeProxy = proxy))
        val input = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("mixed", input.getString("type")); assertEquals("127.0.0.1", input.getString("listen"))
        assertEquals("session-password", input.getJSONArray("users").getJSONObject(0).getString("password"))
        val first = root.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("probe-in", first.getJSONArray("inbound").getString(0)); assertEquals("proxy", first.getString("outbound"))
    }
    @Test fun antiDpiDetourOnlyWrapsEncryptedServerConnection() {
        val root = JSONObject(SingBoxManager.generateConfig(null, node(), AdaptiveRoutePolicy.safeSettings(AppSettings(), RouteProfile.BYEDPI),
            probeProxy = LocalProbeProxy(10810, "probe", "secret"), antiDpiPort = 10820))
        val out = root.getJSONArray("outbounds")
        assertEquals("anti-dpi", out.getJSONObject(0).getString("detour"))
        assertEquals("vless", out.getJSONObject(0).getString("type"))
        assertEquals("server.example.org", out.getJSONObject(0).getJSONObject("tls").getString("server_name"))
        assertFalse(out.getJSONObject(0).getJSONObject("tls").getBoolean("insecure"))
        assertEquals("127.0.0.1", out.getJSONObject(1).getString("server"))
        assertEquals("proxy", root.getJSONObject("route").getString("final"))
    }
    @Test fun tunAddressesHaveV4AndV6Prefixes() {
        val root = JSONObject(SingBoxManager.generateConfig(null, node()))
        val inbound = root.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("172.19.0.1/30", inbound.getJSONArray("address").getString(0))
        assertEquals("fdfe:dcba:9876::1/126", inbound.getJSONArray("address").getString(1))
    }
    @Test fun bootstrapResolverIsOutsideTheServerItIsResolving() {
        val root = JSONObject(SingBoxManager.generateConfig(null, node()))
        assertEquals("local-dns", root.getJSONArray("outbounds").getJSONObject(0).getString("domain_resolver"))
        val dns = root.getJSONObject("dns").getJSONArray("servers")
        assertEquals("direct", dns.getJSONObject(1).getString("detour"))
        assertEquals("proxy", dns.getJSONObject(0).getString("detour"))
    }
    @Test fun fragmentationCannotChangeServerSniOrDisableCertificateChecks() {
        val root = JSONObject(SingBoxManager.generateConfig(null, node(), AutoConnectPolicy.settings(AppSettings(), true)))
        val tls = root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls")
        assertEquals("server.example.org", tls.getString("server_name")); assertFalse(tls.getBoolean("insecure"))
        assertTrue(tls.getBoolean("fragment"))
    }
}
