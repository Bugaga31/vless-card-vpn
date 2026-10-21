package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same bounded-wave orchestration used by Actions.quickTest, not a test-only search. */
class DeferredAutoFallbackR149Test {
    private val now = 1_000_000L
    private fun route(i: Int) = Server("route-$i", "vless", "host-$i.invalid", 443, "fixture-$i")
    private fun evidence(confirmed: Boolean = false, fail: Boolean = false) = ServerState(
        realMs=20, bigOk=true, httpsHosts=2, httpsNetwork="wifi", httpsCheckedAt=now,
        serviceNetwork="wifi", serviceCheckedAt=now,
        serviceTgOk=if (fail) false else if (confirmed) true else null,
        serviceYtOk=if (confirmed) true else null)

    private class Harness {
        var elapsed = 0L
        var generation = 1
        var cancelled = false
        val attempt = AutoForegroundAttempt(1,"wifi","h",{elapsed})
        val calls = mutableListOf<List<Server>>()
        val states = mutableMapOf<String, ServerState>()
        var handoffs = 0
    }
    private suspend fun run(h: Harness, count: Int = 8, probe: suspend (Int, List<Server>) -> Unit): Server? {
        val result = DeferredAutoFallback.search((0 until count).map(::route), h.attempt::searchMs,
            valid={h.generation == 1 && !h.cancelled},
            probeWave={wave -> h.calls.add(wave); probe(h.calls.size,wave)},
            select={observed -> AutoEligibility.connectionPool(observed,{h.states[it.id] ?: ServerState()},"wifi",now).firstOrNull()},
            confirmed={AutoEligibility.serviceRank(h.states.getValue(it.id),"wifi",now) == 0})
        if (result != null) h.handoffs++
        return result
    }
    @Test fun secondWaveServicePassBeatsFirstWaveUnknown() = runBlocking {
        val h=Harness()
        val winner=run(h) { n,wave ->
            assertEquals(0,h.handoffs)
            h.states[wave.first().id]=evidence(confirmed=n==2)
            h.elapsed+=1000
        }
        assertEquals(route(4),winner); assertEquals(2,h.calls.size); assertEquals(1,h.handoffs)
    }
    @Test fun unknownInBothWavesFinalizesExactlyOneHonestFallback() = runBlocking {
        val h=Harness()
        val winner=run(h) { _,wave ->
            assertEquals(0,h.handoffs)
            h.states[wave.first().id]=evidence(); h.elapsed+=1000
        }
        assertNotNull(winner); assertEquals(2,h.calls.size); assertEquals(1,h.handoffs)
        assertNull(h.states.getValue(winner!!.id).serviceTgOk)
        assertNull(h.states.getValue(winner.id).serviceYtOk)
    }
    @Test fun explicitTelegramFailCannotBecomeFallback() = runBlocking {
        val h=Harness()
        val winner=run(h) { _,wave -> h.states[wave.first().id]=evidence(fail=true) }
        assertNull(winner); assertEquals(2,h.calls.size); assertEquals(0,h.handoffs)
    }
    @Test fun exhaustedSearchBudgetFinalizesDataWithoutNewWork() = runBlocking {
        val h=Harness()
        val winner=run(h) { _,wave -> h.states[wave.first().id]=evidence(); h.elapsed=10_000 }
        assertEquals(route(0),winner); assertEquals(1,h.calls.size); assertEquals(1,h.handoffs)
        assertEquals(5000L,h.attempt.remainingMs()) // final-chain reserve, not a restarted deadline
    }
    @Test fun generationChangeDuringSecondWaveDiscardsAllOldResults() = runBlocking {
        val h=Harness()
        val winner=run(h) { n,wave -> h.states[wave.first().id]=evidence(confirmed=n==2); if(n==2) h.generation++ }
        assertNull(winner); assertEquals(2,h.calls.size); assertEquals(0,h.handoffs)
    }
    @Test fun cancellationRejectsEvenANonCooperativeLateServicePass() = runBlocking {
        val h=Harness()
        val job=launch(start=CoroutineStart.LAZY) {
            run(h) { n,wave ->
                h.states[wave.first().id]=evidence(confirmed=n==2)
                if(n==2) currentCoroutineContext().cancel()
            }
        }
        job.start(); job.join()
        assertTrue(job.isCancelled); assertEquals(2,h.calls.size); assertEquals(0,h.handoffs)
    }
    @Test fun confirmedFirstWaveWinsWithoutStartingAnotherWave() = runBlocking {
        val h=Harness()
        val winner=run(h) { _,wave -> h.states[wave.first().id]=evidence(confirmed=true) }
        assertEquals(route(0),winner); assertEquals(1,h.calls.size); assertEquals(1,h.handoffs)
    }
    @Test fun foregroundSearchNeverProbesMoreThanEightCandidates() = runBlocking {
        val h=Harness()
        assertNull(run(h,count=1200) { _,_ -> })
        assertEquals(8,h.calls.flatten().size); assertEquals(2,h.calls.size)
    }
    @Test fun revokedFirstWaveProofIsRecheckedAtFinalization() = runBlocking {
        val h=Harness()
        assertNull(run(h) { n,wave ->
            if(n==1) h.states[wave.first().id]=evidence()
            else h.states[route(0).id]=evidence(fail=true)
        })
        assertEquals(0,h.handoffs)
    }
    @Test fun youtubeFailAlsoExcludesFallback() = runBlocking {
        val h=Harness()
        assertNull(run(h) { _,wave -> h.states[wave.first().id]=evidence().copy(serviceYtOk=false) })
        assertEquals(0,h.handoffs)
    }
    @Test fun phaseTimeoutPreservesExistingDataProofButDoesNotStartNewWave() = runBlocking {
        val h=Harness(); h.elapsed=9975
        val winner=run(h) { _,wave ->
            h.states[wave.first().id]=evidence(); h.elapsed=10_000
            awaitCancellation()
        }
        assertEquals(route(0),winner); assertEquals(1,h.calls.size); assertEquals(1,h.handoffs)
    }
    @Test fun alreadyExpiredSearchStartsNoWork() = runBlocking {
        val h=Harness(); h.elapsed=10_000
        assertNull(run(h) { _,_ -> fail("work started past search deadline") })
        assertTrue(h.calls.isEmpty()); assertEquals(0,h.handoffs)
    }

}
