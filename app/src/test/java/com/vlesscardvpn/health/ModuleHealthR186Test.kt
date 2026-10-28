package com.vlesscardvpn.health

import com.vlesscardvpn.core.Probe
import org.junit.Assert.*
import org.junit.Test

class ModuleHealthR186Test {
    private val good=HealthInput(true,100,true,true,true,120,true)
    private val requirements=HealthRequirements()
    private fun assess(input:HealthInput=good,r:HealthRequirements=requirements)=ModuleHealthPolicy.assess(input,r)
    private fun state(a:HealthAssessment,m:HealthModule)=a.observations.single{it.module==m}.status
    @Test fun allRequiredModulesPass() {
        val a=assess(); assertEquals(true,a.ready); assertEquals(HealthAction.NONE,a.action)
        assertEquals(5,a.observations.map{it.module}.distinct().size)
    }
    @Test fun latencyBoundaryIsInclusiveAndNoArtificialMinimum() {
        for (ms in listOf(1,49,50,499,500)) {
            assertEquals(true,assess(good.copy(httpsMs=ms,telegramMs=ms)).ready)
            assertTrue(ModuleHealthPolicy.fastLink(true,ms,true,ms))
        }
    }
    @Test fun slowHttpsSuggestsOptimizationNotBlindRestart() {
        val a=assess(good.copy(httpsMs=501)); assertEquals(false,a.ready)
        assertEquals(ModuleStatus.SLOW,state(a,HealthModule.HTTPS)); assertEquals(HealthAction.OPTIMIZE,a.action)
    }
    @Test fun slowTelegramCannotBeGreen() {
        val a=assess(good.copy(telegramMs=501)); assertEquals(false,a.ready)
        assertEquals(ModuleStatus.SLOW,state(a,HealthModule.TELEGRAM)); assertEquals(HealthAction.OPTIMIZE,a.action)
    }
    @Test fun missingAndNegativeTimingAreUnknownNotFailure() {
        for(ms in listOf(0,-1)) {
            val a=assess(good.copy(telegramMs=ms)); assertNull(a.ready)
            assertEquals(ModuleStatus.UNKNOWN,state(a,HealthModule.TELEGRAM))
            assertNull(a.observations.single{it.module==HealthModule.TELEGRAM}.latencyMs)
            assertEquals(HealthAction.RECHECK,a.action)
        }
    }
    @Test fun dnsFailureDoesNotEraseHttpsSuccess() {
        val a=assess(good.copy(dns=false)); assertEquals(false,a.ready)
        assertEquals(ModuleStatus.PASS,state(a,HealthModule.HTTPS)); assertEquals(ModuleStatus.FAIL,state(a,HealthModule.DNS))
        assertEquals(HealthAction.RECHECK_DNS,a.action)
    }
    @Test fun unknownRequiredDnsIsNotHealthy() { assertNull(assess(good.copy(dns=null)).ready) }
    @Test fun proxyOnlyDisablesDnsRequirement() {
        val a=assess(good.copy(dns=null),requirements.copy(dns=false))
        assertEquals(true,a.ready); assertEquals(ModuleStatus.DISABLED,state(a,HealthModule.DNS))
    }
    @Test fun bodyFailureOverridesFastService() {
        val a=assess(good.copy(payload=false)); assertEquals(false,a.ready)
        assertEquals(HealthAction.REPAIR_ROUTE,a.action)
    }
    @Test fun unmeasuredBodyIsNotInventedSuccessOrFailure() {
        val a=assess(good.copy(payload=null)); assertEquals(true,a.ready)
        assertEquals(ModuleStatus.DISABLED,state(a,HealthModule.PAYLOAD))
    }
    @Test fun telegramFailureHasSpecificReason() {
        val a=assess(good.copy(telegram=false)); assertEquals(false,a.ready)
        assertEquals(HealthAction.REPAIR_TELEGRAM,a.action)
    }
    @Test fun unknownTelegramIsNotAFalsePass() { assertNull(assess(good.copy(telegram=null)).ready) }
    @Test fun manualModeDoesNotRequireUnrequestedServices() {
        val a=assess(good.copy(telegram=false,youtube=false,httpsMs=900),requirements.copy(
            telegram=false,strictLatency=false,rejectKnownMediaFailure=false))
        assertEquals(true,a.ready); assertEquals(ModuleStatus.DISABLED,state(a,HealthModule.TELEGRAM))
        assertEquals(ModuleStatus.DISABLED,state(a,HealthModule.YOUTUBE))
    }
    @Test fun requestedMediaUnknownRequiresRecheck() {
        val a=assess(good.copy(youtube=null),requirements.copy(youtube=true))
        assertNull(a.ready); assertEquals(HealthAction.RECHECK,a.action)
    }
    @Test fun optionalUnknownMediaDoesNotBlock() {
        val a=assess(good.copy(youtube=null)); assertEquals(true,a.ready)
        assertEquals(ModuleStatus.DISABLED,state(a,HealthModule.YOUTUBE))
    }
    @Test fun knownMediaFailureInAutoIsNotIgnored() {
        val a=assess(good.copy(youtube=false)); assertEquals(false,a.ready)
        assertEquals(HealthAction.RECHECK_MEDIA,a.action)
    }
    @Test fun blockedRouteOverridesAllPositiveModules() {
        val a=ModuleHealthPolicy.assess(good,requirements,blocked=true)
        assertEquals(false,a.ready); assertEquals(HealthAction.REPAIR_ROUTE,a.action)
    }
    @Test fun failurePriorityIsDeterministic() {
        assertEquals(HealthAction.REPAIR_ROUTE,assess(good.copy(https=false,dns=false,telegram=false)).action)
        assertEquals(HealthAction.RECHECK_DNS,assess(good.copy(dns=false,telegram=false)).action)
        assertEquals(HealthAction.REPAIR_TELEGRAM,assess(good.copy(telegram=false,youtube=false)).action)
    }
    @Test fun coordinatorPreservesDnsAndPayloadEvidence() {
        val a=LiveHealthCoordinator.assess(Probe(100,true,null,tgOk=true,tgMs=120),false,true,true,false,true)
        assertEquals(false,a.ready); assertEquals(ModuleStatus.PASS,state(a,HealthModule.HTTPS))
        assertEquals(ModuleStatus.FAIL,state(a,HealthModule.DNS))
    }
    @Test fun coordinatorManualAndRequiredMediaRules() {
        val p=Probe(100,null,null,tgOk=null)
        assertEquals(true,LiveHealthCoordinator.assess(p,true,true,false,false,false).ready)
        assertNull(LiveHealthCoordinator.assess(p,true,true,false,true,false).ready)
        assertNull(LiveHealthCoordinator.assess(p,true,true,true,false,true).ready)
    }
    @Test fun reportIsFixedLocalMetadataNotTrafficOrAiAnalysis() {
        val json=LiveHealthCoordinator.report(assess())
        assertEquals("local_socks_probes_not_full_app_tun",json.getString("scope"))
        assertFalse(json.getBoolean("traffic_capture")); assertFalse(json.getBoolean("ml_ids"))
        assertFalse(json.getBoolean("automatic_upload")); assertEquals(5,json.getJSONArray("modules").length())
        assertEquals(setOf("schema","scope","ready","suggested_action","modules","traffic_capture","ml_ids","automatic_upload"),json.keySet())
    }
    @Test fun reportPreservesUnknownAsJsonNull() {
        val json=LiveHealthCoordinator.report(assess(good.copy(telegram=null)))
        assertTrue(json.isNull("ready"))
        val tg=json.getJSONArray("modules").getJSONObject(3)
        assertEquals("UNKNOWN",tg.getString("status")); assertTrue(tg.isNull("latency_ms"))
    }
}
