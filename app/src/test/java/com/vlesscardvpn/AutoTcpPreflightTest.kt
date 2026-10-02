package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTcpPreflightTest {
    private fun node(n: Int) = VlessConfig(id = "node-$n", name = "Node $n", address = "n$n.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "example.org")

    @Test fun budgetRetainsAllUnknownRoutesAndCompletedHints() = runTest {
        val rows = (1..24).map(::node)
        val result = AutoTcpPreflight.measure(rows) { c ->
            if (c.id == "node-1") { delay(10); 40 } else { delay(5000); 99 }
        }
        assertEquals(AutoTcpPreflight.BUDGET_MS, currentTime)
        assertEquals(rows, result.map { it.first })
        assertEquals(40, result.first().second)
        assertTrue(result.drop(1).all { it.second == -1 })
    }
    @Test fun concurrencyAndCandidateCountAreBounded() = runTest {
        var active = 0; var peak = 0; var calls = 0
        val result = AutoTcpPreflight.measure((1..100).map(::node)) {
            active++; calls++; peak = maxOf(peak, active)
            try { delay(100); 8 } finally { active-- }
        }
        assertEquals(24, result.size); assertEquals(24, calls)
        assertEquals(AutoTcpPreflight.PARALLELISM, peak); assertEquals(0, active)
        assertEquals(300L, currentTime)
    }
    @Test fun cancellationIsNotTurnedIntoAnUnknownRouteResult() = runTest {
        var released = 0
        val job = async { AutoTcpPreflight.measure((1..24).map(::node)) {
            try { awaitCancellation() } finally { released++ }
        } }
        runCurrent(); job.cancel(); job.join()
        assertTrue(job.isCancelled)
        assertEquals(AutoTcpPreflight.PARALLELISM, released)
    }
    @Test fun duplicateImportedIdsCannotMixHints() = runTest {
        val a = node(1); val b = node(2).copy(id = a.id)
        val result = AutoTcpPreflight.measure(listOf(a, b)) { if (it.address == a.address) 10 else -1 }
        assertEquals(listOf(10, -1), result.map { it.second })
    }
    @Test fun emptyPoolDoesNotConsumeDeadline() = runTest {
        assertTrue(AutoTcpPreflight.measure(emptyList()) { error("No probe expected") }.isEmpty())
        assertEquals(0L, currentTime)
    }
    @Test fun tcpSuccessIsOnlyAHintNotVpnHealth() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val hint = AutoTcpPreflight.tcpHint(node(1).copy(address = "127.0.0.1", port = listener.localPort))
            listener.accept().use { assertTrue(hint > 0) }
            assertFalse(TunnelHealthReport().internet)
        }
    }
    @Test fun stalledResolverCannotHoldCallerAndSocketCloses() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val captured = AtomicReference<Socket>()
        try {
            val result = withTimeout(3000) {
                AutoTcpPreflight.tcpHint(node(1), timeoutMs = 250) { socket, _, _ ->
                    captured.set(socket); started.countDown()
                    try { release.await(5, TimeUnit.SECONDS) } finally { finished.countDown() }
                }
            }
            assertEquals(-1, result)
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(captured.get().isClosed)
        } finally {
            release.countDown(); finished.await(2, TimeUnit.SECONDS)
        }
    }
}
