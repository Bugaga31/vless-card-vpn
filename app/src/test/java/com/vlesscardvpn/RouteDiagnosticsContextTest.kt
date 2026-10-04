package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class RouteDiagnosticsContextTest {
    @Test fun staleCleanupCannotClearAnotherSession() {
        val old = LocalProbeProxy.allocate(); val current = LocalProbeProxy.allocate()
        try {
            TunnelHealthChecker.activate(old, RouteProfile.FRAGMENT)
            TunnelHealthChecker.activate(current, RouteProfile.BYEDPI, ByeDpiPreset.TCP_ONLY)
            TunnelHealthChecker.clear(old)
            assertSame(current, TunnelHealthChecker.activeProxy)
            TunnelHealthChecker.clear(current.copy()) // Equal fields are not the same session object.
            assertSame(current, TunnelHealthChecker.activeProxy)
        } finally { TunnelHealthChecker.clear(current); TunnelHealthChecker.clear(old) }
    }
    @Test fun legacyRowsRemainReadableWithoutInventedStage() {
        val file = File.createTempFile("legacy-network", ".log")
        try {
            file.writeText("1 | HTTPS_CHECK | YOUTUBE | BYEDPI | TCP_ONLY | TIMEOUT | -1 | -1")
            val ring = DiagnosticRing(file)
            assertEquals("1 | HTTPS_CHECK | YOUTUBE | BYEDPI | TCP_ONLY | TIMEOUT | -1 | -1 | NONE", ring.read().single())
            ring.append(NetworkDiagnosticEvent(phase = DiagnosticPhase.HTTPS_CHECK, failure = DiagnosticFailure.TIMEOUT, stage = DiagnosticFailure.TLS))
            assertEquals(2, ring.read().size); assertTrue(ring.read().last().endsWith(" | TLS"))
        } finally { file.delete() }
    }
    @Test fun arbitraryStageTextCannotBeExported() {
        val file = File.createTempFile("invalid-stage", ".log")
        try {
            file.writeText("1 | HTTPS_CHECK | YOUTUBE | BYEDPI | TCP_ONLY | TIMEOUT | -1 | 300 | https://private-token")
            assertTrue(DiagnosticRing(file).read().isEmpty())
        } finally { file.delete() }
    }
    @Test fun refusedLocalListenerIsConnectNotSocksFailure() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = TunnelHealthChecker.probe(LocalProbeProxy(port, "test", "not-a-user-secret"), "https://example.org/", 204, 1000)
        assertEquals(DiagnosticFailure.CONNECT, result.failure)
        assertEquals(DiagnosticFailure.CONNECT, result.stage)
        assertTrue(result.latencyMs!! > 0)
    }
    @Test fun checkRecordsTheExactActiveProfileAndPreset() = runBlocking {
        val file = File.createTempFile("route-context", ".log")
        val oldRing = NetworkDiagnosticLog.ring
        val proxy: LocalProbeProxy
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            proxy = LocalProbeProxy(server.localPort, "test", "not-a-user-secret")
            val worker = Thread {
                try { repeat(3) { server.accept().use { socket -> socket.getOutputStream().write(byteArrayOf(5, 0xff.toByte())); socket.getOutputStream().flush() } } }
                catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            try {
                NetworkDiagnosticLog.ring = DiagnosticRing(file)
                TunnelHealthChecker.activate(proxy, RouteProfile.BYEDPI, ByeDpiPreset.TLS_RECORD_ONLY)
                assertFalse(TunnelHealthChecker.check(proxy, 1500, retries = 0).internet)
                val rows = NetworkDiagnosticLog.ring!!.read()
                assertEquals(3, rows.size)
                for (line in rows) {
                    val fields = line.split(" | ")
                    assertEquals("BYEDPI", fields[3]); assertEquals("TLS_RECORD_ONLY", fields[4]); assertEquals("PROXY", fields[8])
                }
            } finally {
                TunnelHealthChecker.clear(proxy); NetworkDiagnosticLog.ring = oldRing
                worker.join(2000); file.delete()
            }
        }
    }
}
