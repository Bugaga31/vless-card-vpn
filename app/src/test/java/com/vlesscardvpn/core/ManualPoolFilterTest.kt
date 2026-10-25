package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class ManualPoolFilterTest {
    private fun keep(hints: Map<String,Int>, selected: List<String> = listOf("a","b","dead")) = ManualPoolFilter.retain(selected,{it},hints)
    @Test fun explicitlyUnreachableDoesNotEnterInitialPool() { assertEquals(listOf("a","b"),keep(mapOf("a" to 3,"b" to 5,"dead" to 0))) }
    @Test fun timeoutIsUnknownNotFailure() { assertEquals(listOf("a","b","dead"),keep(mapOf("dead" to -1))) }
    @Test fun untestedSelectionRemainsExplicit() { assertEquals(listOf("b","dead"),keep(mapOf("a" to 0))) }
    @Test fun noRouteIsInventedIfAllAreUnreachable() { assertTrue(keep(mapOf("a" to 0,"b" to 0,"dead" to 0)).isEmpty()) }
    @Test fun singleSelectionKeepsExistingAuthenticatedProbePath() { assertEquals(listOf("dead"),keep(mapOf("dead" to 0),listOf("dead"))) }
    @Test fun neverAddsEndpointsOrChangesOrder() { assertEquals(listOf("a","b","dead"),keep(mapOf("other" to 1))) }
}
