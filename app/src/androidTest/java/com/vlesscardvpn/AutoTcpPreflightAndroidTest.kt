package com.vlesscardvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vlesscardvpn.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android socket/deadline behavior; not a VPN/Auto UI or DPI bypass test. */
@RunWith(AndroidJUnit4::class)
class AutoTcpPreflightAndroidTest {
    private fun node(port: Int) = VlessConfig(name = "Controlled TCP hint", address = "127.0.0.1", port = port,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "fixture.test")

    @Test fun localTcpHintDoesNotClaimAuthenticatedVpn() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val result = withTimeout(3000) { AutoTcpPreflight.tcpHint(node(server.localPort)) }
            server.accept().use { assertTrue(result > 0) }
            assertFalse(TunnelHealthReport().internet)
        }
    }
    @Test fun deadlineClosesSocketWithoutWaitingForStalledResolver() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val captured = AtomicReference<Socket>()
        try {
            val result = withTimeout(3000) { AutoTcpPreflight.tcpHint(node(443), 400) { socket, _, _ ->
                captured.set(socket); entered.countDown()
                try { release.await(5, TimeUnit.SECONDS) } finally { finished.countDown() }
            } }
            assertEquals(-1, result)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(captured.get().isClosed)
        } finally { release.countDown(); finished.await(2, TimeUnit.SECONDS) }
    }
}
