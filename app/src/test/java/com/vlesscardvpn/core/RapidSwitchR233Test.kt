package com.vlesscardvpn.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RapidSwitchR233Test {
    private fun source(n:String)=listOf(File("src/main/java/com/vlesscardvpn/$n"),File("app/src/main/java/com/vlesscardvpn/$n")).first{it.exists()}.readText()
    private val current=listOf("host1/native","host2/native")
    private val masks=listOf(listOf("host1/best","host1/explore"),listOf("host2/best","host2/explore"))
    @Test fun rememberedMasksRaceBeforeNativeWaveFinishes() {assertEquals(listOf("host1/native","host1/best","host2/native","host2/best","host1/explore","host2/explore"),RecoveryWavePolicy.arrange(current,masks,5))}
    @Test fun oneSlotStaysBoundedAndIncludesMask() {assertEquals(listOf("host1/native","host1/best"),RecoveryWavePolicy.arrange(current,masks,1))}
    @Test fun threeSlotsRetainBothHosts() {val p=RecoveryWavePolicy.arrange(current,masks,3);assertEquals(6,p.size);assertTrue(p.take(3).containsAll(current))}
    @Test fun tenSlotsDoNotExpandMaskSweep() {assertEquals(6,RecoveryWavePolicy.arrange(current,masks,10).size)}
    @Test fun emptyAlternativesStillTryNativeRoutes() {assertEquals(current,RecoveryWavePolicy.arrange(current,emptyList(),5))}
    @Test fun missingSecondMaskPoolIsSafe() {assertEquals(listOf("host1/native","host1/best","host2/native","host1/explore"),RecoveryWavePolicy.arrange(current,masks.take(1),5))}
    @Test fun duplicateVariantsAreNotRetested() {assertEquals(listOf("one"),RecoveryWavePolicy.arrange(listOf("one","one"),listOf(listOf("one")),5))}
    @Test fun emptyWaveIsSafe() {assertTrue(RecoveryWavePolicy.arrange(emptyList<String>(),emptyList(),5).isEmpty())}
    @Test fun fullBatchHasNormalServiceBudget() {assertEquals(7500L,RecoveryWavePolicy.budgetMs(4,5))}
    @Test fun queuedBatchHasBoundedSeparateBudget() {assertEquals(15000L,RecoveryWavePolicy.budgetMs(6,3));assertEquals(15000L,RecoveryWavePolicy.budgetMs(2,1));assertEquals(18000L,RecoveryWavePolicy.budgetMs(100,1))}
    @Test fun onlyFailureConfirmationUsesOneSecond() {assertEquals(1000L,RecoveryWavePolicy.confirmDelayMs(true));assertEquals(3000L,RecoveryWavePolicy.confirmDelayMs(false));assertTrue(VpnRecoveryPolicy.watchPeriod(1000,true)>=10000L);assertEquals(20000L,VpnRecoveryPolicy.watchPeriod(1000,true,true))}
    @Test fun successfulFullProfileStillHasNoAgeTimer() {val p=PeriodicServicePolicy();p.completed("r",100,true);assertFalse(p.due("r",999999))}
    @Test fun selectedUnknownOrFailCannotWin() {val p=AutoCheckProfile.of(listOf("youtube","voice"));assertFalse(p.accepts(Probe(100,null,null,callsOk=true),3000));assertFalse(p.accepts(Probe(100,true,false,callsOk=true),3000));assertTrue(p.accepts(Probe(100,true,true,callsOk=true),3000))}
    @Test fun maskWinnerCancelsStalledNativeAndCleansUp() = runBlocking {
        var cleaned=false;var winner=-1
        val started=CompletableDeferred<Unit>()
        ProbeRace.run(listOf(0,1),2,1,probe={i->
            if(i==0) {try {started.complete(Unit);awaitCancellation()} finally {cleaned=true}}
            else {started.await();Probe(100,true,true)}
        },onEach={i,p->if(p.works) winner=i})
        assertEquals(1,winner);assertTrue(cleaned)
    }
    @Test fun raceRespectsUserOneSlot() = runBlocking {
        var active=0;var peak=0
        ProbeRace.run(listOf(0,1,2),1,1,probe={i->active++;peak=maxOf(peak,active);yield();active--;if(i==2)Probe(100,true,true)else Probe(0,null,null)},onEach={_,_->})
        assertEquals(1,peak)
    }
    @Test fun emergencyUsesSameCoreWithCompleteProfileAndFirstWinner() {val s=source("core/Actions.kt").substringAfter("private suspend fun quickMaskRescue").substringBefore("private suspend fun chooseCompared");assertTrue(s.contains("RecoveryWavePolicy.arrange"));assertTrue(s.contains("autoChecks=profile"));assertTrue(s.contains("stopAfter=1"));assertTrue(s.contains("parallel=slots"));assertTrue(s.contains("finally {own.close()}"))}
    @Test fun firstEmergencyPassIncludesNewHostsAndMasks() {val s=source("core/Actions.kt").substringAfter("suspend fun autoRescue").substringBefore("suspend fun repairTelegram");assertTrue(s.contains("includeCurrent=true"));assertFalse(s.contains("it.id !in attempted && st.state(it).works"));assertTrue(s.contains("ForegroundProbePlan.cohorts(ranked,true)"))}
    @Test fun launchWaitsForCleanupAndExactContext() {val s=source("core/Actions.kt").substringAfter("private suspend fun quickMaskRescue").substringBefore("private suspend fun chooseCompared");assertTrue(s.contains("MaskSamplePolicy.unchanged"));assertTrue(s.contains("AutoChecks.current!=profile"));assertTrue(s.contains("Net.underlying(app)?.toString()!=network"));assertTrue(s.contains("if(!engineReady(mask)) return null"))}
    @Test fun rokiAndMaskMemoryUseExistingRealContext() {val s=source("core/Actions.kt").substringAfter("private suspend fun quickMaskRescue").substringBefore("private suspend fun chooseCompared");assertTrue(s.contains("MaskExperiment.memory.choose(memoryContext,network"));assertTrue(s.contains("Roki.session.observe(Roki.context(net,profile,profileLimit"));assertTrue(s.contains("mask.id!=expected[server.id]"))}
    @Test fun liveTunnelWaitsForTwoFailuresAndProtectsCall() {val s=source("vpn/TunnelService.kt");assertTrue(s.contains("RecoveryWavePolicy.confirmDelayMs(autoWatch)"));assertTrue(s.contains("miss >= 2"));assertTrue(s.contains("VoiceCallPolicy.deferRepair(callActive, miss)"));assertTrue(s.contains("core===sampledCore"))}
}
