package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StabilityRecoveryConfirmationR244Test {
    private val profile=AutoCheckProfile.of(listOf("stability","telegram","youtube"))
    private val good=Probe(100,true,true,tgOk=true,tgMs=100,stableOk=true)
    private fun quality(reason:String)=good.copy(stableOk=false,serviceNotes=mapOf("stability" to reason))
    @Test fun responseQualityIsStillStrictFailure() {
        for(r in listOf("STABILITY_SPREAD","STABILITY_LATENCY")) {
            val p=quality(r);assertTrue(RequiredServiceRecovery.failed(profile,p));assertTrue(RequiredServiceRecovery.qualityOnly(profile,p));assertFalse(profile.accepts(p,3000))
        }
    }
    @Test fun realOrUnknownReasonsAndOtherFailureAreNotDeferred() {
        for(r in listOf("STABILITY_RESPONSE_FAILED","STABILITY_INCOMPLETE","OTHER","")) assertFalse(RequiredServiceRecovery.qualityOnly(profile,quality(r)))
        assertFalse(RequiredServiceRecovery.qualityOnly(profile,quality("STABILITY_SPREAD").copy(ytOk=false)))
        assertFalse(RequiredServiceRecovery.qualityOnly(AutoCheckProfile.of(listOf("youtube")),quality("STABILITY_SPREAD")))
    }
    @Test fun twoSamplesSameRouteAreRequiredAndRecoveryIsRateLimited() {
        val c=StabilityRecoveryConfirmation();assertEquals(30000L,c.remaining("a",1000));assertEquals(1L,c.remaining("a",30999));assertEquals(0L,c.remaining("a",31000));c.attempted(31000)
        assertEquals(120000L,c.remaining("b",31000));assertEquals(1L,c.remaining("b",150999));assertEquals(0L,c.remaining("b",151000))
    }
    @Test fun successAndContextChangeDiscardOldConfirmation() {
        val c=StabilityRecoveryConfirmation();c.remaining("a",1000);assertEquals(30000L,c.remaining("b",31000));c.clear();assertEquals(30000L,c.remaining("b",61000));assertEquals(30000L,c.remaining("b",5))
    }
    @Test fun wiringKeepsWatchdogAndStrictRecovery() {
        val s=listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"),File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first{it.exists()}.readText()
        assertTrue(s.contains("scheduleQualityRecheck(sampledServiceKey"));assertTrue(s.contains("qualityVerifyJob?.cancel()"));assertTrue(s.contains("else if (!callActive)"));assertTrue(s.contains("watchdog(); standbyScout()"));assertTrue(s.contains("RequiredServiceRecovery.failed(profile,p)"))
    }
}
