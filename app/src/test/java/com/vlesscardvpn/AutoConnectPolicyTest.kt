package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test

class AutoConnectPolicyTest {
    private fun node() = VlessConfig(name = "TLS", address = "vpn.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", sni = "vpn.example.org", flow = "")
    @Test fun rejectsPlaintextAndUnsupportedTransport() {
        assertFalse(AutoConnectPolicy.supports(node().copy(security = "none")))
        assertFalse(AutoConnectPolicy.supports(node().copy(transport = "xhttp")))
        assertTrue(AutoConnectPolicy.supports(node()))
    }
    @Test fun rejectsBrokenRealityAndPreservesOnlyCanonicalUuid() {
        assertFalse(AutoConnectPolicy.supports(node().copy(security = "reality", publicKey = "invalid")))
        assertFalse(AutoConnectPolicy.supports(node().copy(uuid = "1-1-1-1-1")))
        assertFalse(AutoConnectPolicy.supports(node().copy(port = 65536)))
    }
    @Test fun deduplicationPreservesDifferentTransportsAndCredentialsStayOutOfKey() {
        val a = node(); val b = a.copy(transport = "ws", wsPath = "/a"); val c = b.copy(wsPath = "/b")
        assertEquals(3, AutoConnectPolicy.rank(listOf(a, b, c, a)).size)
        assertFalse(AutoConnectPolicy.identity(a).contains(a.uuid))
    }
    @Test fun favoritesRestrictionCannotFetchUnpermittedNodes() {
        assertEquals(1, AutoConnectPolicy.rank(listOf(node(), node().copy(id = "f", isFavorite = true)), true).size)
    }
    @Test fun automaticSettingsAreSessionOnlyAndAvoidRandomSniAndBypasses() {
        val original = AppSettings(enableRuDirect = true, enableAdBlock = true, enableSniRotation = true, bypassApps = listOf("org.telegram.messenger"))
        val auto = AutoConnectPolicy.settings(original, false)
        assertFalse(auto.enableRuDirect); assertFalse(auto.enableAdBlock); assertFalse(auto.enableSniRotation)
        assertTrue(auto.bypassApps.isEmpty()); assertFalse(auto.enableFragmentation)
        assertTrue(original.enableRuDirect)
        assertTrue(AutoConnectPolicy.settings(original, true).enableFragmentation)
    }
    @Test fun genericInternetDoesNotMeanYoutubeOrTelegramWorks() {
        val r = TunnelHealthReport(listOf(TunnelProbe("Cloudflare", 100, 204)), 1)
        assertTrue(r.internet); assertFalse(r.preferredServices)
        assertFalse(TunnelHealthReport(listOf(TunnelProbe("Cloudflare", 100, 302)), 1).internet)
    }
}
