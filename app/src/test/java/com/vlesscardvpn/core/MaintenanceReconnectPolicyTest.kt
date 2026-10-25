package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class MaintenanceReconnectPolicyTest {
    @Test fun initialConnectionHasAStableWindow() {
        assertEquals(30_000L, MaintenanceReconnectPolicy.remaining(100,100))
        assertEquals(1L, MaintenanceReconnectPolicy.remaining(30_099,100))
        assertEquals(0L, MaintenanceReconnectPolicy.remaining(30_100,100))
    }
    @Test fun monotonicRegressionCannotApplyEarly() { assertEquals(30_000L, MaintenanceReconnectPolicy.remaining(99,100)) }
    @Test fun evenOneByteOfSlowTrafficDefers() {
        assertFalse(MaintenanceReconnectPolicy.idle(100,200,101,200))
        assertFalse(MaintenanceReconnectPolicy.idle(100,200,100,201))
    }
    @Test fun unknownOrResetCountersAreNotIdle() {
        assertFalse(MaintenanceReconnectPolicy.idle(-1,-1,-1,-1))
        assertFalse(MaintenanceReconnectPolicy.idle(100,200,99,200))
    }
    @Test fun idleSameGenerationCanApply() {
        assertTrue(MaintenanceReconnectPolicy.idle(100,200,100,200))
        assertTrue(MaintenanceReconnectPolicy.mayApply(true,true,true,true))
    }
    @Test fun staleOrStoppedSessionCanNeverApply() {
        assertFalse(MaintenanceReconnectPolicy.mayApply(false,true,false,true))
        assertFalse(MaintenanceReconnectPolicy.mayApply(false,true,true,true))
    }
    @Test fun activeAndYoungConnectionsAreProtected() {
        assertFalse(MaintenanceReconnectPolicy.mayApply(true,true,true,false))
        assertFalse(MaintenanceReconnectPolicy.mayApply(true,false,false,true))
    }
    @Test fun explicitOptOutDoesNotBypassSessionOrStartupGuards() {
        assertTrue(MaintenanceReconnectPolicy.mayApply(true,true,false,false))
        assertFalse(MaintenanceReconnectPolicy.mayApply(true,false,false,false))
    }
    @Test fun optionalProbeCannotCompeteWithInitialTlsHandshake() {
        assertFalse(MaintenanceReconnectPolicy.mayProbe(100,100,true))
        assertFalse(MaintenanceReconnectPolicy.mayProbe(30_099,100,true))
        assertTrue(MaintenanceReconnectPolicy.mayProbe(30_100,100,true))
    }
    @Test fun newConnectionRestartsProtectionWithoutResettingAutoBudget() {
        assertTrue(MaintenanceReconnectPolicy.mayProbe(60_000,100,true))
        assertFalse(MaintenanceReconnectPolicy.mayProbe(60_000,60_000,true))
        assertFalse(MaintenanceReconnectPolicy.mayProbe(99,100,true))
    }
    @Test fun startupExpiryCannotOverridePowerOrTrafficProtection() {
        assertFalse(MaintenanceReconnectPolicy.mayProbe(60_000,100,false))
    }
    @Test fun optionalDeferralDoesNotSuppressEmergencyRepair() {
        val busy = !MaintenanceReconnectPolicy.mayProbe(100,100,true)
        assertNull(AppWatch.pilot(0,true,busy,600_000,7_200_000))
        // Production bypasses optional/power busy only for two actual failed checks.
        val failures = 2
        assertEquals("fix", AppWatch.pilot(failures,true,busy && failures < 2,600_000,7_200_000))
    }
}
