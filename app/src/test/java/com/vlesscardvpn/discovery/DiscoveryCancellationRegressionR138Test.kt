package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryCancellationRegressionR138Test {
    private fun server(i: Int) = Server("test-$i", "vless", "host$i.example", 443, "id-$i")
    @Test fun recentFailureIsDeferredNotBlacklisted() {
        val list = (0..12).map(::server)
        val pick = ConnectPolicy.candidates(list, listOf(list[0]), setOf(list[1].id), cooling = { it.id == list[1].id }) { 0 }
        assertEquals(list[0], pick.first()); assertFalse(pick.contains(list[1]))
        assertEquals(listOf(list[1]), ConnectPolicy.candidates(listOf(list[1]), emptyList(), emptySet(), cooling = { true }) { 0 })
    }
    @Test fun cooldownIsNetworkAndMaskScoped() {
        val memory = ProbeMemory(); memory.record("wifi", "id", "a", false, 1000)
        assertTrue(memory.cooling("wifi", "id", "a", 1001))
        assertFalse(memory.cooling("mobile", "id", "a", 1001)); assertFalse(memory.cooling("wifi", "id", "b", 1001))
    }
    @Test fun cooldownExpiresAndSuccessClearsIt() {
        val memory = ProbeMemory(); memory.record("n", "s", "m", false, 1000)
        assertFalse(memory.cooling("n", "s", "m", 121000))
        memory.record("n", "s", "m", false, 122000); memory.record("n", "s", "m", true, 122001)
        assertFalse(memory.cooling("n", "s", "m", 122002))
    }
    @Test fun memoryIsBoundedAndBackwardsClockDoesNotExtendCooldown() {
        val memory = ProbeMemory(capacity = 2)
        (0..2).forEach { memory.record("n", "$it", "", false, 1000) }
        assertFalse(memory.cooling("n", "0", "", 1001)); assertTrue(memory.cooling("n", "2", "", 1001))
        assertFalse(memory.cooling("n", "2", "", 999))
    }
    @Test fun firstSuccessCancelsSlowSiblingsWithoutNegativeResults() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cleaned = AtomicInteger()
        val results = java.util.concurrent.ConcurrentHashMap<Int, Probe>()
        withTimeout(1500) {
            ProbeRace.run(listOf(0, 1), 2, 1, probe = { i ->
                if (i == 0) { started.complete(Unit); try { delay(10_000); Probe(0, null, null) } finally { cleaned.incrementAndGet() } }
                else { started.await(); Probe(20, null, null) }
            }, onEach = { i, p -> results[i] = p })
        }
        assertEquals(1, cleaned.get()); assertTrue(results.getValue(1).works)
        assertSame(Tester.SKIPPED, results[0]); assertFalse(results.values.any { !it.works && it !== Tester.SKIPPED })
    }
    @Test fun fullCheckStillCollectsEveryResult() = runBlocking {
        val results = mutableListOf<Probe>()
        ProbeRace.run((0..7).toList(), 2, Int.MAX_VALUE, probe = { delay(1); Probe(it + 1, true, true) }, onEach = { _, p -> results += p })
        assertEquals(8, results.size); assertTrue(results.all { it.works })
    }
    @Test fun cancelledParentIsNotConvertedToSuccessOrFailure() = runBlocking {
        val start = CompletableDeferred<Unit>(); val results = AtomicInteger()
        val task = launch { ProbeRace.run(listOf(0), 1, 1, probe = { start.complete(Unit); delay(10_000); Probe(1, null, null) }, onEach = { _, _ -> results.incrementAndGet() }) }
        start.await(); task.cancelAndJoin(); assertEquals(0, results.get())
    }
    @Test fun skippedCandidatesDoNotRunProbes() = runBlocking {
        val calls = AtomicInteger(); val results = mutableListOf<Probe>()
        ProbeRace.run(listOf(0, 1), 1, 1, skip = { true }, probe = { calls.incrementAndGet(); Probe(1, null, null) }, onEach = { _, p -> results += p })
        assertEquals(0, calls.get()); assertEquals(2, results.size); assertTrue(results.all { it === Tester.SKIPPED })
    }
    @Test fun cancellationCallsTransportHookAndInterruptsWorker() = runBlocking {
        val started = CompletableDeferred<Unit>(); val closed = AtomicInteger(); val finished = CountDownLatch(1)
        val task = launch {
            CancellableProbe.run {
                bind -> bind { closed.incrementAndGet() }; started.complete(Unit)
                try { Thread.sleep(10_000) } finally { finished.countDown() }
            }
        }
        withTimeout(2000) { started.await() }; task.cancelAndJoin()
        assertTrue(finished.await(2, TimeUnit.SECONDS)); assertEquals(1, closed.get())
    }
    @Test fun transportResultAndExceptionPropagateNormally() = runBlocking {
        assertEquals(42, CancellableProbe.run { 42 })
        try { CancellableProbe.run<Int> { error("transport-error") }; fail("expected exception") }
        catch (e: IllegalStateException) { assertEquals("transport-error", e.message) }
    }
    @Test fun raceConcurrencyIsBounded() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        ProbeRace.run((0..11).toList(), 3, Int.MAX_VALUE, probe = {
            val n = active.incrementAndGet(); peak.updateAndGet { old -> maxOf(old, n) }
            try { delay(5); Probe(0, null, null) } finally { active.decrementAndGet() }
        }, onEach = { _, _ -> })
        assertEquals(3, peak.get()); assertEquals(0, active.get())
    }
    @Test fun cancellationClosesAnActualHttpSocket() = runBlocking {
        val listener = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val headersSent = CountDownLatch(1); val peerClosed = CountDownLatch(1)
        val server = Thread {
            runCatching {
                listener.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".toByteArray()); flush()
                    }
                    headersSent.countDown()
                    if (socket.getInputStream().read() == -1) peerClosed.countDown()
                }
            }
        }.apply { isDaemon = true; start() }
        val client = okhttp3.OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val task = launch {
            CancellableProbe.run { bind ->
                val call = client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${listener.localPort}/").build())
                bind { call.cancel() }
                call.execute().use { it.body!!.byteStream().read() }
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(headersSent.await(3, TimeUnit.SECONDS)) }
            task.cancelAndJoin()
            withContext(Dispatchers.IO) { assertTrue(peerClosed.await(2, TimeUnit.SECONDS)) }
        } finally {
            task.cancelAndJoin(); listener.close(); server.join(1000)
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
    }

}
