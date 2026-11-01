package com.vlesscardvpn.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Lifecycle wiring regressions. Actual packet transfer is gated by API 29/33 E2E. */
class BridgeRecoveryR239Test {
    private fun source(path: String): String = listOf(File(path), File("../$path")).first { it.isFile }.readText()
    private val service get() = source("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")
    private val bridge get() = source("app/src/main/java/com/vlesscardvpn/core/TProxyService.kt")
    @Test fun softRecoveryRenewsBridgeAfterCoreStart() {
        assertTrue(service.indexOf("c.start(config)") < service.indexOf("TProxyService.resume("))
        assertTrue(service.contains("if (!proxy && keepTun)"))
    }
    @Test fun teardownStopsBridgeBeforeCore() {
        val s=service.substringAfter("private fun teardown(")
        assertFalse(s.contains("if (!keepTun) TProxyService.stop()"))
        assertTrue(s.indexOf("TProxyService.stop()") < s.indexOf("core?.stop()"))
    }
    @Test fun retainedTunIsNotClosedBySoftTeardown() {
        assertTrue(service.contains("if (!keepTun) { Tunnel.socks = null; runCatching { tun?.close() }; tun = null }"))
    }
    @Test fun softBridgeUsesSavedConfigNotNewInterface() {
        val s=bridge.substringAfter("fun resume(").substringBefore("fun stop()")
        assertTrue(s.contains("check(f.isFile)"));assertTrue(s.contains("check(TProxyStartService(f.absolutePath, tun.fd))"))
        assertFalse(s.contains("writeText"));assertFalse(s.contains("establish("))
    }
    @Test fun initialBridgeStartCannotFailSilently() {
        val s=bridge.substringAfter("fun start(").substringBefore("fun resume(")
        assertTrue(s.contains("check(TProxyStartService(f.absolutePath, tun.fd))"))
    }
    @Test fun nativeRetainsExternallyOwnedDescriptor() {
        val s=source("native/hev-socks5-tunnel/src/hev-socks5-tunnel.c").substringAfter("tunnel_fini (void)")
        assertTrue(s.indexOf("if (!tun_fd_local)") < s.indexOf("hev_tunnel_close (tun_fd)"))
    }
    @Test fun backgroundTransferGateIsNotReplacedByInternalProbe() {
        val s=source("tools/e2e-android.sh")
        assertTrue(s.contains("R=\$(probe background-recovered); okprobe"))
        assertTrue(s.contains("background: userspace bridge was not renewed on retained TUN"))
        assertTrue(s.contains("BRIDGES_BEFORE=\$(logcount 'bridge resumed keepTun=true')"))
    }
}
