package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FastRecoveryR230Test {
    private fun profile(vararg s:String)=AutoCheckProfile.of(s.toList())
    private val good=Probe(100,true,true,tgOk=true,tgMs=100,discordOk=true,callsOk=true,instagramOk=true,stableOk=true)
    private fun source(n:String)=listOf(File("src/main/java/com/vlesscardvpn/$n"),File("app/src/main/java/com/vlesscardvpn/$n")).first{it.exists()}.readText()
    @Test fun activeDetectorIsBoundedAndNotEverySecond() {for(v in listOf(-1L,1000L,10000L,120000L))assertTrue(VpnRecoveryPolicy.watchPeriod(v,true) in 10000L..15000L)}
    @Test fun quietDetectorRemainsFiniteAndLight() {assertEquals(20000L,VpnRecoveryPolicy.watchPeriod(120000,true,true));assertTrue(VpnRecoveryPolicy.healthOnly(true,true))}
    @Test fun urgentPathRetainsTwoMissesAndFastRetry() {val s=source("vpn/TunnelService.kt");assertTrue(s.contains("RecoveryWavePolicy.confirmDelayMs(autoWatch)"));assertTrue(s.contains("miss >= 2"));assertEquals(1000L,VpnRecoveryPolicy.RETRY_MS)}
    @Test fun lightDoesNotMeanScreenOff() {val s=source("vpn/TunnelService.kt");assertTrue(s.contains("healthyMs,autoWatch,asleep()"));assertFalse(s.contains("healthyMs,autoWatch,quietAuto"))}
    @Test fun lightProbeHasOwnBoundedBudget() {assertTrue(source("vpn/TunnelService.kt").contains("budgetMs = if(healthOnly) 3500L else 6500L"))}
    @Test fun selectedYoutubeFailureRecovers() {assertTrue(RequiredServiceRecovery.failed(profile("youtube"),good.copy(ytOk=false)))}
    @Test fun selectedDiscordFailureRecovers() {assertTrue(RequiredServiceRecovery.failed(profile("discord"),good.copy(discordOk=false)))}
    @Test fun selectedVoiceFailureRecovers() {assertTrue(RequiredServiceRecovery.failed(profile("voice"),good.copy(callsOk=false)))}
    @Test fun selectedInstagramFailureRecovers() {assertTrue(RequiredServiceRecovery.failed(profile("instagram"),good.copy(instagramOk=false)))}
    @Test fun selectedStabilityFailureRecovers() {assertTrue(RequiredServiceRecovery.failed(profile("stability"),good.copy(stableOk=false)))}
    @Test fun unknownCannotTriggerSwitch() {assertFalse(RequiredServiceRecovery.failed(profile("youtube","discord","voice","instagram"),good.copy(ytOk=null,discordOk=null,callsOk=null,instagramOk=null)))}
    @Test fun unselectedFailuresDoNotTriggerSwitch() {assertFalse(RequiredServiceRecovery.failed(profile(),good.copy(ytOk=false,callsOk=false,discordOk=false,instagramOk=false,stableOk=false,tgOk=false)))}
    @Test fun serviceFailureCannotApproveRoute() {val p=good.copy(ytOk=false);assertFalse(profile("youtube").accepts(p,3000));assertTrue(RequiredServiceRecovery.failed(profile("youtube"),p))}
    @Test fun unknownStillCannotApproveRoute() {assertFalse(profile("youtube").accepts(good.copy(ytOk=null),3000))}
    @Test fun overviewPrecedesUpdateAndClipboard() {val s=source("ui/HomeScreen.kt");assertTrue(s.indexOf("ConnectionOverview(")<s.indexOf("Updater.state"));assertTrue(s.indexOf("ConnectionOverview(")<s.indexOf("clip?.let"))}
    @Test fun checkAndServiceChipsPrecedeRuntimeDetails() {val s=source("ui/ConnectionOverview.kt");assertTrue(s.indexOf("status.check.isNotBlank()")<s.indexOf("runtime.entries.forEachIndexed"));assertTrue(s.indexOf("HomeServiceChips(")<s.indexOf("runtime.entries.forEachIndexed"));assertTrue(s.contains("LiveRegionMode.Polite"))}
    @Test fun userCanDisconnectBeforeVerboseCheck() {val s=source("ui/ConnectionOverview.kt");assertTrue(s.indexOf("control()")<s.indexOf("status.check.isNotBlank()"))}
    @Test fun recoveryProgressIsPublished() {assertTrue(source("vpn/TunnelService.kt").contains("onStep={healStatus"))}
    @Test fun watsonSamplingRespondsToPressureWithoutInventory() {assertEquals(120000L,AutomaticCoachPolicy.sampleDelay(true,true));assertEquals(60000L,AutomaticCoachPolicy.sampleDelay(true,false));assertEquals(120000L,AutomaticCoachPolicy.sampleDelay(false,false));assertEquals(300000L,AutomaticCoachPolicy.sampleDelay(false,true));assertTrue(source("core/DeviceCoach.kt").contains("includePhoneInfo = visible.value && plan.allowLight"))}
    @Test fun searchInvalidatesFutureTrainingWindow() {assertTrue(source("core/DeviceCoach.kt").contains("if(searching) PhoneBrain.invalidateWindow()"));assertTrue(source("core/PhoneBrain.kt").contains("pending=null; forecastAt=0L"))}
    @Test fun foregroundSlotsNeverReducedByWatson() {val plan=PowerPolicy.evaluate(PowerPolicy.Input(0,battery=5,temp=46.0),PowerPolicy.Options());for(n in listOf(1,3,5,10))assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,plan,true))}
    @Test fun rokiOneClassDoesNotPretendConfidence() {val m=RokiRanker();val a=Server("a","vless","192.0.2.1",443,"fixture");val b=a.copy(address="192.0.2.2");repeat(80){m.observe("ctx",ServerState(realMs=10,bigOk=true),true)};assertEquals(listOf(b,a),m.order("ctx",listOf(b,a)){if(it==a)ServerState(realMs=10,bigOk=true)else ServerState(realMs=2000)})}
    @Test fun rokiNearIdenticalStatesKeepBaselineAndAllCandidates() {val m=RokiRanker();repeat(20){m.observe("ctx",ServerState(),true);m.observe("ctx",ServerState(realMs=2000),false)};val routes=(1..80).map{Server("s$it","vless","192.0.2.$it",443,"fixture")};assertEquals(routes,m.order("ctx",routes){ServerState(realMs=100)})}
    @Test fun unknownAndCancelDoNotTrainRoki() {val m=RokiRanker();repeat(20){m.observe("ctx",ServerState(),null);m.observe("ctx",ServerState(),true,true)};assertEquals(0,m.samples("ctx"))}
}
