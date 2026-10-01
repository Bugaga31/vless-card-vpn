package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test

class AdaptiveRoutePolicyTest {
    private val tls = VlessConfig(name = "Test", address = "vpn.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "vpn.example.org")
    @Test fun realProfilesHaveDeterministicOrder() { assertEquals(RouteProfile.entries.toList(), AdaptiveRoutePolicy.profiles(tls)) }
    @Test fun rememberedSuccessRunsFirstButUnknownDoesNotInjectProfile() {
        assertEquals(RouteProfile.BYEDPI, AdaptiveRoutePolicy.profiles(tls, "BYEDPI").first())
        assertEquals(RouteProfile.COMPATIBLE, AdaptiveRoutePolicy.profiles(tls, "fake_packets").first())
    }
    @Test fun antiDpiDoesNotOperateOnPlainShadowsocksOrEnableBypass() {
        val ss = tls.copy(protocolType = "ss", security = "none")
        assertEquals(listOf(RouteProfile.COMPATIBLE), AdaptiveRoutePolicy.profiles(ss, "BYEDPI"))
        val settings = AdaptiveRoutePolicy.safeSettings(AppSettings(), RouteProfile.BYEDPI)
        assertFalse(settings.enableRuDirect); assertFalse(settings.enableSniRotation)
        assertFalse(settings.enableFragmentation); assertTrue(settings.bypassApps.isEmpty())
    }
}
