package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.RouteEvidence
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRuntimePolicyTest {
    @Test fun moderateThermalHasTwoSocketLimitEvenWithTemperatureGuardOff() {
        val p=PowerPolicy.evaluate(PowerPolicy.Input(1, thermal=2),PowerPolicy.Options(profile="fast",autoCool=false))
        assertFalse(p.allowed); assertFalse(p.allowLight); assertEquals(2,p.tcpParallel);assertEquals(2,p.realParallel)
    }
    @Test fun lowMemoryDefersOptionalWorkNotUserPreferences() {
        val p=PowerPolicy.evaluate(PowerPolicy.Input(1,lowMemory=true),PowerPolicy.Options())
        assertFalse(p.allowed); assertFalse(p.allowLight); assertEquals(2,PowerPolicy.cap(6,p.tcpParallel))
    }
    @Test fun gamingTemporarilyDefersOptionalWork() {
        val p=PowerPolicy.evaluate(PowerPolicy.Input(1,gaming=true),PowerPolicy.Options())
        assertFalse(p.allowed);assertFalse(p.allowLight);assertTrue(p.reason.contains("Игровая"));assertEquals(2,p.realParallel)
        assertTrue(PowerPolicy.evaluate(PowerPolicy.Input(2),PowerPolicy.Options()).allowed)
    }
    @Test fun screenStateIsAvailableForEventDrivenWaits() {
        val off=PowerPolicy.evaluate(PowerPolicy.Input(1,screen=false),PowerPolicy.Options())
        assertFalse(off.screenInteractive);assertFalse(off.allowLight)
        assertTrue(PowerPolicy.evaluate(PowerPolicy.Input(2),PowerPolicy.Options()).screenInteractive)
    }
    @Test fun sessionIsBoundedMonotonicAndResettable() {
        assertFalse(GameSession.valid(0,1));assertFalse(GameSession.valid(10,10));assertFalse(GameSession.valid(Long.MAX_VALUE,1))
        GameSession.set(true,100);assertTrue(GameSession.active(101));assertFalse(GameSession.active(100+GameSession.MAX_MS))
        GameSession.set(true,100);GameSession.set(false,200);assertFalse(GameSession.active(201))
    }
    @Test fun wifiMtuUsesCompatibilityValueNotJumbo() { assertEquals(1400,TransportPolicy.mtu(false,false));assertEquals(1400,TransportPolicy.mtu(false,false,9000)) }
    @Test fun mobileAndWarpReduceVirtualMtu() { assertEquals(1360,TransportPolicy.mtu(true,false));assertEquals(1280,TransportPolicy.mtu(true,true));assertEquals(1280,TransportPolicy.mtu(false,true)) }
    @Test fun mtuNeverGoesBelowIpv6MinimumFromBadLinkHints() { assertEquals(1400,TransportPolicy.mtu(false,false,900));assertEquals(1300,TransportPolicy.mtu(false,false,1300));assertEquals(1360,TransportPolicy.mtu(true,false,1500)) }
    @Test fun quicSniffOnlyWhenQuicIsEnabled() { assertEquals(listOf("http","tls"),TransportPolicy.sniff(false));assertTrue(TransportPolicy.sniff(true).contains("quic")) }
    @Test fun jitterAndFailuresWorsenLatencyRanking() {
        val clean=RouteEvidence(checks=5,successes=5,ema=.95,latency=100.0,jitter=5.0)
        assertTrue(ServerChoice.latencyScore(clean,100)<ServerChoice.latencyScore(clean.copy(jitter=100.0),100))
        assertTrue(ServerChoice.latencyScore(clean,100)<ServerChoice.latencyScore(clean.copy(ema=.2),100))
        assertTrue(ServerChoice.latencyScore(clean,100)<ServerChoice.latencyScore(RouteEvidence(),0))
    }
    private fun phone(active: Boolean=false, low: Boolean=false) = PhoneInfo("test","14",34,8,4.0,.2,20.0,90,false,false,false,false,"hostname","Wi-Fi",false,50,10,privateDnsActive=active,lowMemory=low)
    @Test fun privateDnsActiveIsInformationNotAutomaticConflict() {
        assertFalse(phone().problems().any { it.contains("Частный DNS") })
        assertTrue(phone(true).problems().single().contains("не ошибка"))
    }
    @Test fun busyRamIsNotLowMemoryWithoutSystemFlag() {
        assertFalse(phone().problems().any { it.contains("памяти") })
        assertTrue(phone(low=true).problems().single().contains("памяти"))
    }
    @Test fun quietLogsAndRouteOnlySniffAreActuallyInXrayConfig() {
        val node=Server("n","vless","node.example",443,"00000000-0000-0000-0000-000000000001")
        val s=Settings(mode=com.vlesscardvpn.model.Mode.SERVERS,blockQuic=true,ruWhitelist=false)
        val c=JSONObject(XrayConfigBuilder.vpnConfig(listOf(node to null),s,null,quietLogs=true))
        assertEquals("error",c.getJSONObject("log").getString("loglevel"))
        val sniff=c.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
        assertTrue(sniff.getBoolean("routeOnly"));assertEquals(2,sniff.getJSONArray("destOverride").length())
        assertFalse(c.toString().contains("\"loglevel\":\"none\""))
    }
    @Test fun idleOrBackgroundStatusDoesNotRunAnInfiniteAnimation() {
        assertFalse(TransportPolicy.animateStatus(false,true,true));assertFalse(TransportPolicy.animateStatus(true,false,true))
        assertFalse(TransportPolicy.animateStatus(true,true,false));assertTrue(TransportPolicy.animateStatus(true,true,true))
    }
    @Test fun gameModeNeverChangesSplitTunnelOrPersistentSettings() {
        val s=Settings(perApp=true,onlyApps=false,apps=listOf("chosen.app"),ruAppsDirect=false)
        GameSession.set(true,100);assertEquals(s,Settings.fromJson(s.toJson()));GameSession.set(false,101)
    }
}
