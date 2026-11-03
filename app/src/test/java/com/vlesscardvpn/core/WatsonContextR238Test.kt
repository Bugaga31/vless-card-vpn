package com.vlesscardvpn.core

import com.vlesscardvpn.model.*
import com.vlesscardvpn.watson.WatsonAuditPolicy as Audit
import org.junit.Assert.*
import org.junit.Test

class WatsonContextR238Test {
    private val settings=Settings()
    private val path=NetworkHandoff.Snapshot("fixture-network","wifi","dns")
    private val server=Server("fixture","vless","node.invalid",443,"fixture-secret")
    private fun context(s:Settings=settings)=MaskTrialFence.context(s)
    private fun reserves()=WarmReserveMemory().apply { record(path,server,".b",context(),1000) }
    private fun ready(m:WarmReserveMemory,s:Settings=settings)=m.candidates(path,listOf(server),{".b"},context(s),1001)
    @Test fun identicalConfigurationKeepsSpare() { assertEquals(listOf(server),ready(reserves())) }
    @Test fun sameMaskIdWithNewCustomArgsCannotReuseProof() { assertTrue(ready(reserves(),settings.copy(byeDpiArgs="-s2")).isEmpty()) }
    @Test fun changedFakeSniCannotReuseProof() { assertTrue(ready(reserves(),settings.copy(byeDpiSni="other.invalid")).isEmpty()) }
    @Test fun changedEngineCannotReuseProof() { assertTrue(ready(reserves(),settings.copy(dpiStrategy="custom")).isEmpty()) }
    @Test fun newlyLearnedCurrentDpiInvalidatesOldSpare() { assertTrue(ready(reserves(),settings.copy(dpiRemembered=mapOf("wifi" to "new-strategy"))).isEmpty()) }
    @Test fun newlyLearnedDpiAlsoFencesInFlightTrial() { assertFalse(MaskTrialFence.settingsUnchanged(settings,settings.copy(dpiRemembered=mapOf("wifi" to "new")))) }
    @Test fun requiredServicesMustMatch() { assertTrue(ready(reserves(),settings.copy(autoChecks=setOf("youtube"))).isEmpty()) }
    @Test fun latencyCeilingMustMatch() { assertTrue(ready(reserves(),settings.copy(autoLatencyMaxMs=800)).isEmpty()) }
    @Test fun profileBodyMustMatch() { assertTrue(ready(reserves(),settings.copy(myMasks=listOf("changed-json"))).isEmpty()) }
    @Test fun evolvedPoolMustMatch() { assertTrue(ready(reserves(),settings.copy(dpiEvoPool=listOf("changed-strategy"))).isEmpty()) }
    @Test fun automaticMasksMustMatch() { assertTrue(ready(reserves(),settings.copy(autoMasks=listOf("changed-json"))).isEmpty()) }
    @Test fun testEndpointMustMatch() { assertTrue(ready(reserves(),settings.copy(testUrl="https://other.invalid")).isEmpty()) }
    @Test fun themeDoesNotInvalidateSpare() { assertEquals(listOf(server),ready(reserves(),settings.copy(theme="light"))) }
    @Test fun userParallelismNeverInvalidatesSpare() { for (n in listOf(1,3,5,10)) assertEquals(listOf(server),ready(reserves(),settings.copy(probeParallel=n))) }
    @Test fun phoneAdviceDoesNotInvalidateSpare() { assertEquals(listOf(server),ready(reserves(),settings.copy(phoneLearning=false,automaticSecurityAudit=false))) }
    @Test fun contextCopiesMutableListsAndMaps() {
        val masks=mutableListOf("original");val remembered=mutableMapOf("wifi" to "old")
        val s=settings.copy(myMasks=masks,dpiRemembered=remembered);val old=context(s)
        masks[0]="edited";remembered["wifi"]="new"
        assertNotEquals(old,context(s));assertEquals(context(settings.copy(myMasks=listOf("original"),dpiRemembered=mapOf("wifi" to "old"))),old)
    }
    @Test fun contextNeverPrintsProfileStrings() { val t=context(settings.copy(byeDpiArgs="private-profile",testUrl="private-url")).toString();assertFalse(t.contains("private")) }
    @Test fun contextUsesEqualityNotHashAsProof() { assertEquals("Aa".hashCode(),"BB".hashCode());assertNotEquals(context(settings.copy(byeDpiArgs="Aa")),context(settings.copy(byeDpiArgs="BB"))) }
    @Test fun noFirstAttemptPenalty() { assertTrue(Audit.attemptDue(0,-1)) }
    @Test fun failureCannotSpinOptionalAudit() { for (t in 1000L until 61000L step 1000) assertFalse(Audit.attemptDue(t,1000)) }
    @Test fun retryIsBoundedNotPermanentDisable() { assertTrue(Audit.attemptDue(61000,1000)) }
    @Test fun explicitAuditCanBypassCooldown() { assertTrue(Audit.attemptDue(1001,1000,true)) }
    @Test fun elapsedClockRollbackDoesNotBlockAuditForever() { assertTrue(Audit.attemptDue(999,1000)) }
    @Test fun packageMutationDuringScanCannotPublishCurrentSnapshot() { assertFalse(Audit.publishable(4,5));assertTrue(Audit.publishable(5,5)) }
    @Test fun auditBudgetIsFiniteAndSeparateFromVpn() { assertEquals(15000L,Audit.SCAN_BUDGET_MS);assertEquals(750L,Audit.COALESCE_MS);assertEquals(900000L,Audit.INTERVAL_MS) }
}
