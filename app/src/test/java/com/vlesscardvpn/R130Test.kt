package com.vlesscardvpn

import com.vlesscardvpn.core.CloudSpeed
import com.vlesscardvpn.core.Report
import org.junit.Assert.*
import org.junit.Test

class R130Test {
    @Test fun sameCloudVersionStillAppliesOnNetworkSwitch() {
        assertFalse(Report.needsNetwork("v1", "v1", "Моб.: A", "Моб.: A"))
        assertTrue(Report.needsNetwork("v1", "v1", "Моб.: B", "Моб.: A"))
        assertTrue(Report.needsNetwork("v2", "v1", "Моб.: A", "Моб.: A"))
        assertTrue(Report.needsNetwork("v1", "v1", "Wi-Fi · 192.168.0.1", "Wi-Fi · 192.168.1.1"))
    }

    @Test fun snapshotContainsAllOperatorsAndClearsMissingOnes() {
        val json = """{"Моб.: A":{"aa":[9000,90,4]},"Моб.: B":{"aa":[0,0,5]}}"""
        assertArrayEquals(intArrayOf(9000,90,4), CloudSpeed.netForFamily(json, "Моб.: A")["aa"])
        assertArrayEquals(intArrayOf(0,0,5), CloudSpeed.netForFamily(json, "Моб.: B")["aa"])
        assertTrue(CloudSpeed.netForFamily(json, "Wi-Fi").isEmpty())
        assertTrue(CloudSpeed.netForFamily("{}", "Моб.: A").isEmpty())
        assertTrue(CloudSpeed.netForFamily("bad", "Моб.: A").isEmpty())
    }

    @Test fun cloudSpeedRefreshesAfterTenMinutesNotAnHour() {
        val t = 10_000_000L
        assertFalse(CloudSpeed.refreshDue(t + 599_999L, t, t, true))
        assertTrue(CloudSpeed.refreshDue(t + 600_000L, t, t, true))
        // Even an empty valid snapshot is cached, rather than downloaded on every action.
        assertFalse(CloudSpeed.refreshDue(t + 120_000L, t, t, true))
        assertTrue(CloudSpeed.refreshDue(t, 0, 0, false))
    }

    @Test fun failedFetchBacksOffAndClockRollbackRecovers() {
        val t = 10_000_000L
        assertFalse(CloudSpeed.refreshDue(t + 59_999L, 0, t, false))
        assertTrue(CloudSpeed.refreshDue(t + 60_000L, 0, t, false))
        assertTrue(CloudSpeed.refreshDue(t - 1, t, t, true))
    }
}
