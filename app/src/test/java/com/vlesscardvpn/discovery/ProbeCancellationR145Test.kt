package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProbeCancellationR145Test {
    @Test fun parentCancellationClosesEveryTransportNotJustLastRegisteredCall() = runBlocking {
        val started=CompletableDeferred<Unit>();val first=AtomicInteger();val second=AtomicInteger()
        val job=launch {
            CancellableProbe.run { bind ->
                bind {first.incrementAndGet()};bind {second.incrementAndGet()}
                started.complete(Unit);Thread.sleep(20000)
            }
        }
        withTimeout(2000) {started.await()};job.cancelAndJoin()
        assertEquals(1,first.get());assertEquals(1,second.get())
    }
    @Test fun lateRegistrationAndRepeatedCancellationCloseExactlyOnce() {
        val c=ProbeCancellation();val first=AtomicInteger();val late=AtomicInteger()
        c.register {first.incrementAndGet()};c.cancel();c.cancel()
        c.register {late.incrementAndGet()};c.cancel();c.finish()
        assertEquals(1,first.get());assertEquals(1,late.get())
    }
    @Test fun aThrowingCancellationHookCannotPreventOtherSocketsFromClosing() {
        val c=ProbeCancellation();val closed=AtomicInteger()
        c.register {error("broken cleanup")};c.register {closed.incrementAndGet()};c.cancel()
        assertEquals(1,closed.get())
    }
    @Test fun normalCompletionDoesNotCallCancellationHooksButLateCallsAreClosed() {
        val c=ProbeCancellation();val normal=AtomicInteger();val late=AtomicInteger()
        c.register {normal.incrementAndGet()};c.finish();c.cancel()
        c.register {late.incrementAndGet()}
        assertEquals(0,normal.get());assertEquals(1,late.get())
    }
    @Test fun concurrentRegistrationAndCancellationLoseNoTransport() {
        val c=ProbeCancellation();val counts=List(128){AtomicInteger()}
        val start=java.util.concurrent.CountDownLatch(1)
        val threads=counts.chunked(16).map { chunk -> kotlin.concurrent.thread {
            start.await();chunk.forEach { counter -> c.register {counter.incrementAndGet()} }
        } }
        start.countDown();c.cancel();threads.forEach {it.join()};c.cancel()
        assertTrue(counts.all {it.get()==1})
    }
}
