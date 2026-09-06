package com.vlesscardvpn

import com.vlesscardvpn.domain.AutoPilotPolicy
import com.vlesscardvpn.domain.VlessConfig
import org.junit.Assert.*
import org.junit.Test

class AutoPilotPolicyTest {
    private val allowed = AutoPilotPolicy(true, true, true, false)
    private fun node(id: String, favorite: Boolean = false) = VlessConfig(
        id = id, name = id, address = "example.invalid", port = 443,
        uuid = "00000000-0000-0000-0000-000000000000", isFavorite = favorite
    )

    @Test fun disabledAutopilotCannotProbeOrSwitch() {
        val policy = allowed.copy(enabled = false)
        assertFalse(policy.canMonitor)
        assertFalse(policy.permits(node("backup", true), "active"))
    }
    @Test fun missingConsentCannotProbeOrSwitch() {
        val policy = allowed.copy(consentGiven = false)
        assertFalse(policy.canMonitor)
        assertFalse(policy.permits(node("backup", true), "active"))
    }
    @Test fun disabledFailoverStillAllowsMonitoring() {
        val policy = allowed.copy(failoverEnabled = false)
        assertTrue(policy.canMonitor)
        assertFalse(policy.canFailover)
        assertFalse(policy.permits(node("backup", true), "active"))
    }
    @Test fun favoritesOnlyNeverFallsBackToNonFavorite() {
        val policy = allowed.copy(favoritesOnly = true)
        assertFalse(policy.permits(node("backup"), "active"))
        assertTrue(policy.permits(node("favorite", true), "active"))
    }
    @Test fun failedServerIsExcludedEvenIfFavorite() {
        assertFalse(allowed.permits(node("active", true), "active"))
    }
    @Test fun absentActiveServerCannotTriggerFailover() {
        assertFalse(allowed.permits(node("backup"), null))
    }
    @Test fun emptyFavoritesDoesNotExpandPermissions() {
        val nodes = listOf(node("a"), node("b"))
        assertTrue(nodes.filter { allowed.copy(favoritesOnly = true).permits(it, "active") }.isEmpty())
    }
    @Test fun permissionFilteringPrecedesScanLimit() {
        val nodes = (1..10).map { node("other-$it") } + node("favorite", true)
        assertEquals(listOf("favorite"), nodes.filter {
            allowed.copy(favoritesOnly = true).permits(it, "active")
        }.take(10).map { it.id })
    }
    @Test fun latestPolicyCanRevokeCandidate() {
        val node = node("backup")
        assertTrue(allowed.permits(node, "active"))
        assertFalse(allowed.copy(favoritesOnly = true).permits(node, "active"))
        assertFalse(allowed.copy(failoverEnabled = false).permits(node, "active"))
    }
    @Test fun unchangedConnectedSessionIsAccepted() {
        assertTrue(AutoPilotPolicy.sameConnection("a", 100L, "a", 100L, true))
    }
    @Test fun disconnectOrManualSwitchInvalidatesProbe() {
        assertFalse(AutoPilotPolicy.sameConnection("a", 100L, "a", 100L, false))
        assertFalse(AutoPilotPolicy.sameConnection("a", 100L, "b", 100L, true))
    }
    @Test fun reconnectToSameServerInvalidatesOldProbe() {
        assertFalse(AutoPilotPolicy.sameConnection("a", 100L, "a", 200L, true))
    }
    @Test fun missingSessionCannotAuthorizeSwitch() {
        assertFalse(AutoPilotPolicy.sameConnection(null, 100L, null, 100L, true))
        assertFalse(AutoPilotPolicy.sameConnection("a", 0L, "a", 0L, true))
    }
}
