package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class TorAutoPolicyTest {
    private val ids = setOf("a", "b")
    private fun enable(m: Map<String, Int>, enabled: Boolean = true, routing: Boolean = false,
                       selected: Boolean = true, expected: Set<String> = ids) =
        TorAutoPolicy.shouldEnable(enabled, routing, selected, expected, m)
    @Test fun allExplicitFailuresEnableTor() { assertTrue(enable(mapOf("a" to 0, "b" to 0))) }
    @Test fun partialUnknownCannotEnableTor() { assertFalse(enable(mapOf("a" to 0))) }
    @Test fun emptyRoundCannotEnableTor() { assertFalse(enable(emptyMap(), expected = emptySet())) }
    @Test fun positiveResultWins() { assertFalse(enable(mapOf("a" to 0, "b" to 12))) }
    @Test fun negativeSentinelIsNotExplicitFailure() { assertFalse(enable(mapOf("a" to 0, "b" to -1))) }
    @Test fun wrongRouteIdentityCannotEnableTor() { assertFalse(enable(mapOf("a" to 0, "c" to 0))) }
    @Test fun disabledOrUnselectedDoesNotEnableTor() {
        val m = mapOf("a" to 0, "b" to 0)
        assertFalse(enable(m, enabled = false)); assertFalse(enable(m, selected = false))
    }
    @Test fun alreadyRoutingDoesNotLoop() { assertFalse(enable(mapOf("a" to 0, "b" to 0), routing = true)) }
    @Test fun singleRouteTorTransitionRequestsReconnect() { assertTrue(TorAutoPolicy.needsReconnect(true, false, 1)) }
    @Test fun removedPinDoesNotSuppressTorReconnect() { assertTrue(TorAutoPolicy.needsReconnect(true, true, 1)) }
    @Test fun pinOnlyRetainsExistingReconnectContract() {
        assertFalse(TorAutoPolicy.needsReconnect(false, true, 1))
        assertTrue(TorAutoPolicy.needsReconnect(false, true, 2))
        assertFalse(TorAutoPolicy.needsReconnect(false, false, 2))
    }
}
