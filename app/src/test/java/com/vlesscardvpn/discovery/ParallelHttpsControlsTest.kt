package com.vlesscardvpn

import com.vlesscardvpn.core.HttpsTransferProbe
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ParallelHttpsControlsTest {
    private val urls = listOf("https://a.invalid/data", "https://b.invalid/data", "https://c.invalid/data")
    private class Fixture(private val req: Request, val launch: (Fixture, Callback) -> Unit) : Call {
        @Volatile var cancelled = false
        override fun request() = req
        override fun execute(): Response = error("No sequential execute allowed")
        override fun enqueue(callback: Callback) = launch(this, callback)
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = Fixture(req, launch)
        fun respond(callback: Callback, size: Int = 65536) = callback.onResponse(this,
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .header("Content-Type", "application/octet-stream")
                .body(ByteArray(size).toResponseBody("application/octet-stream".toMediaType())).build())
    }
    @Test fun stalledFirstOriginDoesNotStarveTwoHealthyHosts() {
        val calls = mutableListOf<Fixture>()
        val result = HttpsTransferProbe.check(urls, budgetMs = 500) { req ->
            Fixture(req) { c, cb -> if (req.url.host != "a.invalid") c.respond(cb) }.also { calls += it }
        }
        assertTrue(result.passed); assertEquals(2, result.hosts); assertEquals(131072, result.bytes)
        assertEquals(3, calls.size); assertTrue(calls.all { it.cancelled })
    }
    @Test fun deadlineCancelsAllOutstandingControlsAndRejectsLateBodies() {
        val calls = mutableListOf<Pair<Fixture, Callback>>()
        val result = HttpsTransferProbe.check(urls, budgetMs = 40) { req ->
            Fixture(req) { c, cb -> calls += c to cb }
        }
        assertFalse(result.passed); assertEquals(0, result.hosts)
        assertEquals(3, calls.size); assertTrue(calls.all { it.first.cancelled })
        calls.forEach { (c, cb) -> c.respond(cb) }
        assertEquals(0, result.hosts); assertEquals(0, result.bytes)
    }
    @Test fun cancellingWaitCancelsEveryActiveCall() {
        val started = CountDownLatch(3)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Fixture>())
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val worker = Thread {
            try {
                HttpsTransferProbe.check(urls) { req ->
                    Fixture(req) { _, _ -> started.countDown() }.also { calls += it }
                }
            } catch (_: java.io.InterruptedIOException) { interrupted.set(true) }
        }
        worker.start()
        try { assertTrue(started.await(2, TimeUnit.SECONDS)); worker.interrupt(); worker.join(2000)
            assertFalse(worker.isAlive); assertTrue(interrupted.get()); assertTrue(calls.all { it.cancelled })
        } finally { worker.interrupt(); worker.join(2000) }
    }
    @Test fun exhaustedBudgetStartsNoNetworkWork() {
        val result = HttpsTransferProbe.check(urls, budgetMs = 0) { error("must not start") }
        assertFalse(result.passed)
    }
    @Test fun oneHealthyHostAndShortBodiesNeverPass() {
        val result = HttpsTransferProbe.check(urls) { req -> Fixture(req) { c, cb ->
            c.respond(cb, if (req.url.host == "a.invalid") 65536 else 1024)
        } }
        assertFalse(result.passed); assertEquals(1, result.hosts)
    }
}
