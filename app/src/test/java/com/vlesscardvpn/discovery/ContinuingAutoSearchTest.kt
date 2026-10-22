package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ContinuingAutoSearchTest {
    @Test fun fastFinalizationDoesNotWaitForWholeCatalogueWhenDataIsProven() = runBlocking {
        var ready = false; var waves = 0; var connects = 0
        val result = ContinuingAutoSearch.search((1..40).toList(), {"$it"}, {110000}, {true},
            probeBatch = { waves++; ready = true }, confirmed = { false },
            select = { it.take(1) }, fallbackReady = { ready },
            connect = { connects++; true })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED,result)
        assertEquals(1,waves); assertEquals(1,connects)
    }
    @Test fun fastWindowWithoutDataProofContinuesSearchingLaterBatches() = runBlocking {
        var waves = 0
        val result = ContinuingAutoSearch.search((1..24).toList(), {"$it"}, {100000}, {true},
            probeBatch = { waves++ }, confirmed = { false },
            select = { it.filter { id -> id == 21 } }, fallbackReady = { true },
            connect = { assertEquals(listOf(21),it); true })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED,result); assertEquals(3,waves)
    }

    @Test fun routeBeyondFirstEightIsActuallyConnected() = runBlocking {
        val waves = mutableListOf<List<Int>>(); val connected = mutableListOf<Int>()
        val result = ContinuingAutoSearch.search((1..20).toList(), {"$it"}, {120000}, {true},
            probeBatch = { waves += it }, select = { it.filter { id -> id == 13 } },
            connect = { connected += it; true })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED, result)
        assertEquals(listOf((1..8).toList(), (9..16).toList()), waves); assertEquals(listOf(13), connected)
    }
    @Test fun failedFinalChainMovesToNextProvenRouteWithoutRepeatingIt() = runBlocking {
        val connected = mutableListOf<Int>(); var probes = 0
        val result = ContinuingAutoSearch.search((1..16).toList(), {"$it"}, {120000}, {true},
            probeBatch = { probes++ }, select = { it.take(1) }, connect = { connected += it; it.first() == 3 })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED,result)
        assertEquals(listOf(1,2,3),connected); assertEquals(1,probes)
    }
    @Test fun noFalseSuccessAfterExhaustingWholeFiniteCatalogue() = runBlocking {
        val tested = mutableListOf<Int>()
        val result = ContinuingAutoSearch.search((1..19).toList(), {"$it"}, {120000}, {true},
            probeBatch = { tested += it }, select = { emptyList() }, connect = { error("No data proof") })
        assertEquals(ContinuingAutoSearch.Outcome.EXHAUSTED,result); assertEquals((1..19).toList(),tested)
    }
    @Test fun singleTotalDeadlineIsNotResetBetweenBatches() = runBlocking {
        var remaining = 16000L; var waves = 0
        val result = ContinuingAutoSearch.search((1..50).toList(), {"$it"}, {remaining}, {true},
            probeBatch = { remaining -= 7000; waves++ }, select = { emptyList() }, connect = { false })
        assertEquals(ContinuingAutoSearch.Outcome.DEADLINE,result); assertEquals(2,waves); assertEquals(2000L,remaining)
    }
    @Test fun changedNetworkDuringProbeCannotConnectOldEvidence() = runBlocking {
        var valid = true
        val result = ContinuingAutoSearch.search(listOf(1), {"$it"}, {120000}, {valid},
            probeBatch = { valid = false }, select = { it }, connect = { error("stale connection") })
        assertEquals(ContinuingAutoSearch.Outcome.STALE,result)
    }
    @Test fun changedGenerationDuringActualChainCannotReturnSuccess() = runBlocking {
        var valid = true
        val result = ContinuingAutoSearch.search(listOf(1), {"$it"}, {120000}, {valid},
            probeBatch = {}, select = { it }, connect = { valid = false; true })
        assertEquals(ContinuingAutoSearch.Outcome.STALE,result)
    }
    @Test fun cancellationDuringProbeNeverStartsConnection() = runBlocking {
        try {
            ContinuingAutoSearch.search(listOf(1), {"$it"}, {120000}, {true},
                probeBatch = { throw CancellationException("cancelled") }, select = { it }, connect = { error("late connection") })
            fail("cancellation swallowed")
        } catch (_: CancellationException) {}
    }
    @Test fun cancellationDuringChainNeverAcceptsLateSuccess() = runBlocking {
        val child = launch {
            ContinuingAutoSearch.search(listOf(1), {"$it"}, {120000}, {true},
                probeBatch = {}, select = { it }, connect = { currentCoroutineContext().cancel(); true })
            fail("late success")
        }
        child.join(); assertTrue(child.isCancelled)
    }
    @Test fun exhaustedDeadlineStartsNeitherProbesNorConnections() = runBlocking {
        val result = ContinuingAutoSearch.search(listOf(1), {"$it"}, {0}, {true},
            probeBatch = { error("expired") }, select = { it }, connect = { error("expired") })
        assertEquals(ContinuingAutoSearch.Outcome.DEADLINE,result)
    }
    @Test fun duplicateCatalogueEntriesAreNeverRetried() = runBlocking {
        val tested = mutableListOf<Int>()
        ContinuingAutoSearch.search(listOf(1,1,2,2), {"$it"}, {120000}, {true},
            probeBatch = { tested += it }, select = { emptyList() }, connect = { false })
        assertEquals(listOf(1,2),tested)
    }
    @Test fun selectionCannotInjectUnprobedCandidate() = runBlocking {
        val result = ContinuingAutoSearch.search(listOf(1), {"$it"}, {120000}, {true},
            probeBatch = {}, select = { listOf(99) }, connect = { error("unprobed") })
        assertEquals(ContinuingAutoSearch.Outcome.EXHAUSTED,result)
    }
    @Test fun continuingDeadlineIncludesStartupAndDoesNotAlterLegacyFastBudget() {
        var clock = 0L
        val session = AutoForegroundAttempt.continuing("wifi","handle",{clock})
        val fast = AutoForegroundAttempt.begin("wifi","handle",{clock})
        assertTrue(session.continuing); assertFalse(fast.continuing)
        clock += 15000
        assertEquals(105000L,session.remainingMs()); assertEquals(0L,fast.remainingMs())
        clock += 105000
        assertEquals(0L,session.remainingMs()); assertFalse(session.valid("wifi","handle"))
    }
    @Test fun closedContinuingSessionRejectsLateResult() = runBlocking {
        val session = AutoForegroundAttempt.continuing("wifi","h",{0L})
        session.close("cancelled")
        try { session.within { fail("closed"); 1 }; fail("late success") } catch (_: CancellationException) {}
    }
    @Test fun serviceUnknownIsDeferredUntilLaterConfirmedRoute() = runBlocking {
        val connected = mutableListOf<Int>()
        val result = ContinuingAutoSearch.search((1..16).toList(), {"$it"}, {120000}, {true},
            probeBatch = {}, confirmed = { it == 12 },
            select = { all -> all.filter { it == 12 }.ifEmpty { all.take(1) } },
            connect = { connected += it; true })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED,result); assertEquals(listOf(12),connected)
    }
    @Test fun unknownOnlyCatalogueUsesOneHonestFallbackAtFinalization() = runBlocking {
        var batches = 0; var connections = 0
        val result = ContinuingAutoSearch.search((1..16).toList(), {"$it"}, {120000}, {true},
            probeBatch = { batches++ }, confirmed = { false }, select = { it.take(1) },
            connect = { connections++; assertEquals(2,batches); true })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED,result); assertEquals(1,connections)
    }
    @Test fun deadlineFinalizesExistingDataProofWithoutLaunchingMoreWork() = runBlocking {
        var remaining = 12000L; var batches = 0; var connections = 0
        val result = ContinuingAutoSearch.search((1..24).toList(), {"$it"}, {remaining}, {true},
            probeBatch = { batches++; remaining = 4000L }, confirmed = { false }, select = { it.take(1) },
            connect = { connections++; true })
        assertEquals(ContinuingAutoSearch.Outcome.CONNECTED,result); assertEquals(1,batches); assertEquals(1,connections)
    }

}
