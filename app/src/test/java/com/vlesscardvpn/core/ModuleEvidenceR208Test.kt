package com.vlesscardvpn.core

import com.vlesscardvpn.health.*
import org.junit.Assert.*
import org.junit.Test

class ModuleEvidenceR208Test {
    private val old=HealthAssessment(HealthModule.entries.map {ModuleObservation(it,ModuleStatus.PASS,100)})
    private val times=HealthModule.entries.associateWith {1000L}
    private fun observation(m:HealthModule,s:ModuleStatus)=ModuleObservation(m,s)
    @Test fun telegramFailureImmediatelySupersedesGreen() {
        val f=ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.TELEGRAM,ModuleStatus.FAIL)),2000)
        assertEquals(ModuleStatus.FAIL,f.assessment.observations.first {it.module==HealthModule.TELEGRAM}.status)
        assertEquals(false,f.assessment.ready)
    }
    @Test fun telegramUnknownDoesNotRetainGreen() {
        val f=ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.TELEGRAM,ModuleStatus.UNKNOWN)),2000)
        assertNull(f.assessment.ready)
    }
    @Test fun lightProbeDoesNotRefreshYoutube() {
        val f=ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.HTTPS,ModuleStatus.PASS)),100000)
        assertEquals(1000L,f.sampledAt[HealthModule.YOUTUBE])
        assertEquals(100000L,f.sampledAt[HealthModule.HTTPS])
    }
    @Test fun lightProbeDoesNotRefreshDnsOrTelegram() {
        val f=ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.HTTPS,ModuleStatus.PASS)),100000)
        assertEquals(1000L,f.sampledAt[HealthModule.DNS]);assertEquals(1000L,f.sampledAt[HealthModule.TELEGRAM])
    }
    @Test fun dnsFailureSupersedesGreenWithoutRefreshingOthers() {
        val f=ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.DNS,ModuleStatus.FAIL)),2000)
        assertEquals(false,f.assessment.ready);assertEquals(1000L,f.sampledAt[HealthModule.HTTPS])
    }
    @Test fun noPreviousEvidenceRemainsUnknown() {
        val f=ModuleEvidencePolicy.merge(null,emptyMap(),listOf(observation(HealthModule.HTTPS,ModuleStatus.PASS)),2000)
        assertNull(f.assessment.ready);assertNull(f.sampledAt[HealthModule.TELEGRAM])
    }
    @Test fun blockedTorRouteCannotBeHealedByOneHttpsResponse() {
        val f=ModuleEvidencePolicy.merge(old.copy(blocked=true),times,listOf(observation(HealthModule.HTTPS,ModuleStatus.PASS)),2000)
        assertTrue(f.assessment.blocked);assertEquals(false,f.assessment.ready)
    }
    @Test fun freshTelegramResultDoesNotCarryOldLatency() {
        val f=ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.TELEGRAM,ModuleStatus.FAIL)),2000)
        assertNull(f.assessment.observations.first {it.module==HealthModule.TELEGRAM}.latencyMs)
    }
    @Test(expected=IllegalArgumentException::class) fun duplicateMeasurementsRejected() {
        ModuleEvidencePolicy.merge(old,times,listOf(observation(HealthModule.HTTPS,ModuleStatus.PASS),observation(HealthModule.HTTPS,ModuleStatus.FAIL)),2000)
    }
    @Test fun unsampledFailureNotErasedByLightProbe() {
        val f=ModuleEvidencePolicy.merge(old.copy(observations=old.observations.map {if(it.module==HealthModule.YOUTUBE) it.copy(status=ModuleStatus.FAIL) else it}),
            times,listOf(observation(HealthModule.HTTPS,ModuleStatus.PASS)),2000)
        assertEquals(false,f.assessment.ready)
    }
}
