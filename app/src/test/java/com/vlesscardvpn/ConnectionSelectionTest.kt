package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test
class ConnectionSelectionTest {
 private val failed = VlessConfig(id = "failed", name = "A", address = "a.example.org", port = 443, uuid = "test")
 private val chosen = VlessConfig(id = "chosen", name = "B", address = "b.example.org", port = 443, uuid = "test", isActive = true)
 @Test fun selectingAfterFailureUsesNewSelection() { assertEquals(chosen, ConnectionSelection.current(listOf(failed, chosen), failed, false)) }
 @Test fun liveSessionDisplaysActualRoute() { assertEquals(failed, ConnectionSelection.current(listOf(failed, chosen), failed, true)) }
 @Test fun deletedFailedRouteDoesNotReappear() { assertEquals(chosen, ConnectionSelection.current(listOf(chosen), failed, false)) }
 @Test fun emptySavedListCannotConnectStaleAttempt() { assertNull(ConnectionSelection.current(emptyList(), failed, false)) }
 @Test fun noExplicitSelectionFallsBackToSavedFirst() { assertEquals(failed, ConnectionSelection.current(listOf(failed), null, false)) }
}
