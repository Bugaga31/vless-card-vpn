package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Settings
import org.junit.Assert.*
import org.junit.Test

class MandatoryBridgesR208RuntimeTest {
    @Test fun automaticRequiresObfs4ByDefault() {assertEquals("obfs4",TorConnectionPolicy.automatic(Settings()).torBridge)}
    @Test fun automaticPreservesWebTunnel() {assertEquals("webtunnel",TorConnectionPolicy.automatic(Settings(torBridge="webtunnel")).torBridge)}
    @Test fun savedLinesAreNotInventedOrErased() {assertEquals("saved",TorConnectionPolicy.automatic(Settings(torBridgeLines="saved")).torBridgeLines)}
    @Test fun oldTorAllNoneMigrates() {assertEquals("obfs4",TorConnectionPolicy.bridged(Settings(torAll=true)).torBridge)}
    @Test fun oldSelectiveNoneMigrates() {assertEquals("obfs4",TorConnectionPolicy.bridged(Settings(torRouting=true)).torBridge)}
    @Test fun automaticChainTorRequiresBridge() {assertEquals("obfs4",TorConnectionPolicy.bridged(Settings(torAuto=true)).torBridge)}
    @Test fun rotatorRequiresBridge() {assertEquals("obfs4",TorConnectionPolicy.bridged(Settings(torRotation=true)).torBridge)}
    @Test fun ordinaryVpnUnchanged() {val s=Settings();assertEquals(s,TorConnectionPolicy.bridged(s))}
    @Test fun noDirectTorFallback() {assertFalse(TorConnectionPolicy.bridged(Settings(torAll=true,torDirect=true)).torDirect)}
    @Test fun unsupportedBridgeDoesNotRemainSelectable() {assertEquals("obfs4",TorConnectionPolicy.bridged(Settings(torAll=true,torBridge="unknown")).torBridge)}
    @Test fun runtimeMigratesLegacyNone() {assertEquals("obfs4",TorConnectionPolicy.runtime(Settings(torAll=true)).torBridge)}
    @Test fun manualTransportRetained() {assertEquals("webtunnel",TorConnectionPolicy.bridged(Settings(torAll=true,torBridge="webtunnel")).torBridge)}
    @Test fun occupiedUnownedListenerRejectedBeforeRouting() {
        java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            try {TorCard.requireOwnedOrFree(listener.localPort);fail("Unowned Tor listener was accepted")}
            catch(expected:IllegalStateException) {assertTrue(expected.message.orEmpty().contains("внешним"))}
        }
    }
    @Test fun unusedListenerCanStartLaterBehindVless() {
        val port=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).use {it.localPort}
        TorCard.requireOwnedOrFree(port)
    }
}
