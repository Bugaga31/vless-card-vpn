package com.vlesscardvpn

import com.vlesscardvpn.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ServiceWebProbeTest {
    private class Fixture(private val req: Request, val launch: (Fixture, Callback) -> Unit) : Call {
        @Volatile var cancelled = false
        override fun request() = req
        override fun execute(): Response = error("Sequential requests forbidden")
        override fun enqueue(callback: Callback) = launch(this, callback)
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = Fixture(req, launch)
        fun respond(cb: Callback, code: Int = 200, html: String = "<!doctype html><html>" + "x".repeat(1100), type: String = "text/html") =
            cb.onResponse(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Content-Type",type).body(html.toResponseBody(type.toMediaType())).build())
    }
    @Test fun bothRequestsStartWithoutWaitingForStalledTelegram() {
        val calls = mutableListOf<Fixture>()
        val result = ServiceWebProbe.check(100) { req -> Fixture(req) { c, cb ->
            if (req.url.host == "www.youtube.com") c.respond(cb)
        }.also { calls += it } }
        assertNull(result.telegram); assertEquals(true,result.youtube)
        assertEquals(ServiceWebProbe.URLS,calls.map { it.request().url.toString() })
        assertTrue(calls.all { it.cancelled })
    }
    @Test fun twoValidHtmlResponsesPassOnlyWebEvidence() {
        val result = ServiceWebProbe.check { req -> Fixture(req) { c, cb -> c.respond(cb) } }
        assertEquals(true,result.telegram); assertEquals(true,result.youtube)
        val p = Probe(10,true,null,httpsHosts=2,httpsChecked=true,web=result)
        assertFalse(AutoEligibility.servicesWork(p)); assertNull(p.tgOk); assertNull(p.ytOk)
        assertTrue(ServiceProbePolicy.describe(p).contains("Telegram Web: доступен"))
        assertTrue(ServiceProbePolicy.describe(p).contains("не проверка сообщений"))
    }
    @Test fun redirectsAndDenialsNeverPass() {
        val result = ServiceWebProbe.check { req -> Fixture(req) { c, cb ->
            c.respond(cb,if(req.url.host == "web.telegram.org") 302 else 403)
        } }
        assertEquals(false,result.telegram); assertEquals(false,result.youtube)
    }
    @Test fun emptyAndNonHtmlBodiesRemainUnknown() {
        val result = ServiceWebProbe.check { req -> Fixture(req) { c, cb ->
            if(req.url.host == "web.telegram.org") c.respond(cb,html="") else c.respond(cb,type="application/json")
        } }
        assertNull(result.telegram); assertNull(result.youtube)
    }
    @Test fun timeoutAndLateResponsesNeverCreatePass() {
        val calls = mutableListOf<Pair<Fixture,Callback>>()
        val result = ServiceWebProbe.check(30) { req -> Fixture(req) { c, cb -> calls += c to cb } }
        assertNull(result.telegram); assertNull(result.youtube); assertTrue(calls.all { it.first.cancelled })
        calls.forEach { it.first.respond(it.second) }
        assertNull(result.telegram); assertNull(result.youtube)
    }
    @Test fun exhaustedBudgetStartsNoRequests() {
        assertEquals(ServiceWebProbe.Result(),ServiceWebProbe.check(0) { error("expired") })
    }
    @Test fun failedTlsOrTransportIsUnknownNotFakeSuccess() {
        val result = ServiceWebProbe.check { req -> Fixture(req) { c, cb -> cb.onFailure(c,IOException("TLS fixture")) } }
        assertNull(result.telegram); assertNull(result.youtube)
    }
    @Test fun cancellationClosesBothRequests() {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Fixture>())
        val started = CountDownLatch(2)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val thread = Thread {
            try { ServiceWebProbe.check { req -> Fixture(req) { _, _ -> started.countDown() }.also { calls += it } } }
            catch (_: java.io.InterruptedIOException) { interrupted.set(true) }
        }
        thread.start()
        try { assertTrue(started.await(2,TimeUnit.SECONDS)); thread.interrupt(); thread.join(2000)
            assertFalse(thread.isAlive); assertTrue(interrupted.get()); assertTrue(calls.all { it.cancelled })
        } finally { thread.interrupt(); thread.join(2000) }
    }
    @Test fun healthLabelsWebSeparatelyAndDoesNotPersistProtocolPass() {
        try {
            ServiceHealth.finish(Probe(10,true,null,web=ServiceWebProbe.Result(true,true)),100,3000)
            assertTrue(ServiceHealth.state.value.webOnly)
            assertEquals(true,ServiceHealth.state.value.telegram)
            assertEquals(true,ServiceHealth.state.value.youtube)
        } finally { ServiceHealth.reset() }
    }
}
