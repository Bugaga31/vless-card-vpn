package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test
class ByeDpiPresetTest {
    private fun node(n: Int) = VlessConfig(name = "Node", address = "n$n.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", sni = "example.org", flow = "")
    @Test fun variantsUseActualDifferentNativeArguments() {
        assertEquals(listOf("--split", "1+s"), ByeDpiPreset.TCP_ONLY.extraArgs)
        assertEquals(listOf("--tlsrec", "1+s"), ByeDpiPreset.TLS_RECORD_ONLY.extraArgs)
        assertEquals(4, ByeDpiPreset.COMBINED.extraArgs.size)
    }
    @Test fun loopbackOnlyAndNoShellOrUntrustedArguments() {
        val args = ByeDpiPreset.TCP_ONLY.arguments(12400)
        assertEquals("127.0.0.1", args[args.indexOf("--ip") + 1])
        assertEquals("12400", args[args.indexOf("--port") + 1])
        assertFalse(args.any { it.contains(';') || it.contains('|') })
    }
    @Test fun invalidPortRejected() {
        try { ByeDpiPreset.COMBINED.arguments(0); fail("Invalid port accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun knownVariantIsRestoredAndUnknownVariantCannotInjectOptions() {
        assertEquals(ByeDpiPreset.TLS_RECORD_ONLY, ByeDpiPreset.order(0, "BYEDPI#TLS_RECORD_ONLY").first())
        assertNull(ByeDpiPreset.remembered("BYEDPI#--fake-packets"))
        assertEquals(ByeDpiPreset.COMBINED, ByeDpiPreset.order(0, "BYEDPI").first())
    }
    @Test fun firstVariantsAreSpreadAcrossNodesWithinTheBudget() {
        val plan = AutoSearchPolicy.plan((1..12).map { node(it) to it })
        assertEquals(ByeDpiPreset.entries.toSet(), plan.drop(12).take(12).map { it.byeDpiPreset }.toSet())
        assertEquals(36, plan.size)
    }
    @Test fun allVariantsExistForSingleNodeWithoutReplacingItsCredentials() {
        val c = node(1); val plan = AutoSearchPolicy.plan(listOf(c to -1))
        assertEquals(ByeDpiPreset.entries.toSet(), plan.filter { it.profile == RouteProfile.BYEDPI }.map { it.byeDpiPreset }.toSet())
        assertTrue(plan.all { it.config == c }); assertEquals(5, plan.size)
    }
    @Test fun confirmedVariantMemoryIsRestoredBeforeOtherRoutes() {
        val c = node(1)
        val first = AutoSearchPolicy.plan(listOf(c to 12), mapOf(AutoConnectPolicy.identity(c) to "BYEDPI#TCP_ONLY")).first()
        assertEquals(RouteProfile.BYEDPI, first.profile); assertEquals(ByeDpiPreset.TCP_ONLY, first.byeDpiPreset)
    }
    @Test fun differentVariantKeysDoNotCollide() {
        val keys = AutoSearchPolicy.plan(listOf(node(1) to 1)).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
