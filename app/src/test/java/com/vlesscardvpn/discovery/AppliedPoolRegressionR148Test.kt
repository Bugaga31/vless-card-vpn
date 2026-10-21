package com.vlesscardvpn

import com.vlesscardvpn.core.AutoRoutePolicy
import com.vlesscardvpn.model.Mode
import org.junit.Assert.*
import org.junit.Test

class AppliedPoolRegressionR148Test {
    @Test fun deferredSelectionRemainsPendingAfterNoOpOptimizeInEveryServerMode() {
        val desired = setOf("live1", "live2", "live3")
        val running = desired + "dead"
        for (mode in listOf(Mode.SERVERS, Mode.HYBRID, Mode.AUTO)) {
            repeat(3) { assertTrue(AutoRoutePolicy.selectionPending(mode, desired, running)) }
            assertFalse(AutoRoutePolicy.selectionPending(mode, desired, desired))
        }
    }
    @Test fun emptyFallbackOrServerlessModeDoesNotCreateReconnectLoop() {
        assertFalse(AutoRoutePolicy.selectionPending(Mode.SERVERS, emptySet(), setOf("fallback")))
        assertFalse(AutoRoutePolicy.selectionPending(Mode.BYEDPI, setOf("stored"), emptySet()))
        assertFalse(AutoRoutePolicy.selectionPending(Mode.AUTO, setOf("a", "b"), linkedSetOf("b", "a")))
    }
}
