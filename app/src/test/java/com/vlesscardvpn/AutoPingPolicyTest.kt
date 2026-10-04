package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test
class AutoPingPolicyTest {
    private fun node(n: Int, fav: Boolean = false) = VlessConfig(name = "Node $n", address = "n$n.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "example.org", isFavorite = fav)
    @Test fun pingOnlyIsDefaultMode() {
        assertEquals(ConnectCheckMode.PING, AppSettings().connectCheckMode)
        assertEquals(ConnectCheckMode.PING, ConnectCheckMode.normalize(null))
        assertEquals(ConnectCheckMode.PING, ConnectCheckMode.normalize("garbage"))
        assertEquals(ConnectCheckMode.HTTPS, ConnectCheckMode.normalize("https"))
        assertEquals("", AppSettings().byeDpiCustomArgs)
    }
    @Test fun bestPingFirstUnreachableDroppedAndBounded() {
        val plan = AutoPingPolicy.plan(listOf(node(1) to 300, node(2) to -1, node(3) to 40, node(4) to 120) +
            (5..12).map { node(it) to 500 + it })
        assertEquals(AutoPingPolicy.MAX_ATTEMPTS, plan.size)
        assertEquals(listOf("n3.example.org", "n4.example.org", "n1.example.org"), plan.take(3).map { it.config.address })
        assertTrue(plan.none { it.config.address == "n2.example.org" })
        assertTrue(plan.all { it.profile == RouteProfile.COMPATIBLE && !it.custom })
        assertTrue(AutoPingPolicy.plan(listOf(node(1) to -1, node(2) to 0)).isEmpty())
    }
    @Test fun favoritesBeatPing() {
        val plan = AutoPingPolicy.plan(listOf(node(1) to 20, node(2, fav = true) to 400))
        assertEquals("n2.example.org", plan.first().config.address)
    }
    @Test fun customStrategyAppliesOnlyToTlsServers() {
        val ss = VlessConfig(name = "ss", address = "s.example.org", port = 8388, uuid = "aes-256-gcm:pass",
            protocolType = "ss", security = "none", flow = "")
        val plan = AutoPingPolicy.plan(listOf(node(1) to 50, ss to 10), customByeDpi = true)
        val tls = plan.first { it.config.address == "n1.example.org" }
        assertEquals(RouteProfile.BYEDPI, tls.profile); assertTrue(tls.custom)
        assertTrue(tls.key.endsWith("#CUSTOM")); assertEquals(AutoRouteAttempt.CUSTOM_LABEL, tls.label)
        val plain = plan.first { it.config.address == "s.example.org" }
        assertEquals(RouteProfile.COMPATIBLE, plain.profile); assertFalse(plain.custom)
    }
    @Test fun rememberedRouteIsReusedInvalidMemoryIgnored() {
        val c = node(1)
        assertEquals(ByeDpiPreset.MASK_FAKE, AutoPingPolicy.route(c, 10, "BYEDPI#MASK_FAKE", false).byeDpiPreset)
        assertEquals(RouteProfile.FRAGMENT, AutoPingPolicy.route(c, 10, "FRAGMENT", false).profile)
        assertEquals(RouteProfile.COMPATIBLE, AutoPingPolicy.route(c, 10, "BYEDPI#--ip", false).profile)
        assertEquals(RouteProfile.COMPATIBLE, AutoPingPolicy.route(c, 10, "FRAGMENT#x", false).profile)
        assertEquals(RouteProfile.COMPATIBLE, AutoPingPolicy.route(c, 10, null, false).profile)
        val p = AutoPingPolicy.plan(listOf(c to 10), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#OOB"))
        assertEquals(ByeDpiPreset.OOB, p.single().byeDpiPreset)
    }
}
