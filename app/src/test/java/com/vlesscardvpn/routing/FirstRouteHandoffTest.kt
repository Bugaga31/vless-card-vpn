package com.vlesscardvpn

import com.vlesscardvpn.core.ConnectPolicy
import org.junit.Assert.*
import org.junit.Test

class FirstRouteHandoffTest {
    @Test fun missingSelectionDoesNotConsumeStart() {
        val gate = ConnectPolicy.StartGate()
        assertFalse(gate.claimSelected { false })
        assertFalse(gate.started)
        assertTrue(gate.claimSelected { true })
        assertTrue(gate.started)
    }
    @Test fun noSecondSelectionOrStartAfterCommittedWinner() {
        val gate = ConnectPolicy.StartGate()
        assertTrue(gate.claimSelected { true })
        assertFalse(gate.claimSelected { error("must not select twice") })
    }
    @Test fun concurrentWinnersCommitOnlyOneSelection() {
        val gate = ConnectPolicy.StartGate()
        val selections = java.util.concurrent.atomic.AtomicInteger()
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val workers = (0..20).map {
            Thread { if (gate.claimSelected { selections.incrementAndGet(); true }) starts.incrementAndGet() }.apply { start() }
        }
        workers.forEach { it.join() }
        assertEquals(1, selections.get())
        assertEquals(1, starts.get())
    }
    @Test fun rejectedOrStaleCandidateAllowsLaterCandidate() {
        val gate = ConnectPolicy.StartGate()
        repeat(3) { assertFalse(gate.claimSelected { false }) }
        assertTrue(gate.claimSelected { true })
    }
}
