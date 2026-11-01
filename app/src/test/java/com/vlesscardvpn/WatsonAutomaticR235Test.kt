package com.vlesscardvpn

import com.vlesscardvpn.watson.WatsonAuditPolicy as P
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatsonAutomaticR235Test {
    @Test fun defaultsEnableReadOnlyAudit() { assertTrue(Settings().automaticSecurityAudit) }
    @Test fun optOutRoundTrips() { assertFalse(Settings.fromJson(Settings().copy(automaticSecurityAudit=false).toJson()).automaticSecurityAudit) }
    @Test fun oldSettingsEnableAudit() { assertTrue(Settings.fromJson(JSONObject()).automaticSecurityAudit) }
    @Test fun firstSnapshotDue() { assertTrue(P.due(0,-1,0,-1)) }
    @Test fun noSecondPolling() { for(t in 0L..899_999L step 1000) assertFalse(P.due(t,0,1,1)) }
    @Test fun periodicRefreshBounded() { assertTrue(P.due(900_000,0,1,1)) }
    @Test fun packageChangeInvalidatesSnapshot() { assertTrue(P.due(10,0,2,1)) }
    @Test fun changeDuringAuditRemainsDirty() { assertTrue(P.due(10,10,3,2)) }
    @Test fun clockRollbackInvalidates() { assertTrue(P.due(9,10,1,1)) }
    @Test fun optOutStopsOptionalWork() { assertFalse(P.allowed(false,false,false,true)) }
    @Test fun userSearchAlwaysWins() { assertFalse(P.allowed(true,true,false,true)) }
    @Test fun thermalOrBatteryPressureDefers() { assertFalse(P.allowed(true,false,false,false)) }
    @Test fun gamingDefers() { assertFalse(P.allowed(true,false,true,true)) }
    @Test fun everyResourceCombinationHonorsUserSearchAndOptOut() {
        for(enabled in listOf(false,true)) for(searching in listOf(false,true))
            for(gaming in listOf(false,true)) for(light in listOf(false,true)) {
                assertEquals(enabled && !searching && !gaming && light, P.allowed(enabled,searching,gaming,light))
            }
    }
    @Test fun safeWindowAllowed() { assertTrue(P.allowed(true,false,false,true)) }
    @Test fun currentDefaultLatencyStillStrict() {
        assertEquals(3000, AutoLatencyPolicy.MAX_MS)
        val s=AutoSearchStatistics(3000); repeat(3) { s.start() }
        s.observe(true,3000,true,3000);s.observe(true,3001,true,100);s.observe(true,100,null,0)
        assertEquals(1,s.snapshot()["ready"]);assertEquals(1,s.snapshot()["over_3000ms"])
        assertEquals(1,s.snapshot()["telegram_unknown"])
    }
}
