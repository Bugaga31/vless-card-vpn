package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks

class AutomaticWorkR226Test {
    private fun plan(i:PowerPolicy.Input=PowerPolicy.Input(100,battery=80),o:PowerPolicy.Options=PowerPolicy.Options(wifiOnly=false))=PowerPolicy.evaluate(i,o)
    @Test fun forecastDefersOptionalWork() {val p=plan(PowerPolicy.Input(100,battery=80,predictedPressure=true));assertFalse(p.allowLight);assertFalse(p.allowReserve);assertFalse(p.allowed);assertFalse(p.memory.hot)}
    @Test fun normalPlanRemainsAvailable() {assertTrue(plan().allowLight);assertTrue(plan().allowReserve)}
    @Test fun hotAutoCannotBypassOptionalBudget() {assertFalse(BackgroundReservePolicy.mayProbe(true,plan(PowerPolicy.Input(100,thermal=5))))}
    @Test fun callAutoCannotBypassOptionalBudget() {assertFalse(BackgroundReservePolicy.mayProbe(true,plan(PowerPolicy.Input(100,voiceCall=true))))}
    @Test fun lowBatteryAutoDefersReserve() {assertFalse(BackgroundReservePolicy.mayProbe(true,plan(PowerPolicy.Input(100,battery=10))))}
    @Test fun forecastAutoDefersReserve() {assertFalse(BackgroundReservePolicy.mayProbe(true,plan(PowerPolicy.Input(100,predictedPressure=true))))}
    @Test fun restrictedReserveHasOnlyTwoCandidates() {assertEquals(2,BackgroundReservePolicy.candidates(plan(o=PowerPolicy.Options(profile="eco",wifiOnly=false))))}
    @Test fun ecoCanKeepTinyCalmReserve() {val p=plan(o=PowerPolicy.Options(profile="eco",wifiOnly=false));assertFalse(p.allowLight);assertTrue(BackgroundReservePolicy.mayProbe(true,p));assertFalse(BackgroundReservePolicy.mayProbe(false,p))}
    @Test fun allUserProbeLimitsRemainUntouched() {val p=plan(PowerPolicy.Input(100,thermal=5,predictedPressure=true));for(n in listOf(1,3,5,10)) for(f in listOf(true,false)) assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,p,f))}
    @Test fun predictionExpires() {assertTrue(PhoneForecastPolicy.fresh(100,101));assertFalse(PhoneForecastPolicy.fresh(100,180100));assertFalse(PhoneForecastPolicy.fresh(100,99));assertFalse(PhoneForecastPolicy.fresh(0,1))}
    @Test fun chargeChangesInvalidateLearningWindow() {assertFalse(PhoneForecastPolicy.cleanWindow(false,true,false));assertFalse(PhoneForecastPolicy.cleanWindow(true,false,false))}
    @Test fun explicitSearchInvalidatesLearningWindow() {assertFalse(PhoneForecastPolicy.cleanWindow(true,true,true));assertTrue(PhoneForecastPolicy.cleanWindow(false,false,false))}
    private val server=Server("fixture","vless","node.example.test",443,"fixture",security="tls",sni="peer.example.test")
    @Test fun boundedServerRotationAdvances() {val all=(0..5).map {server.copy(port=443+it)};assertEquals(all.take(2).map {it.id},AutoMaskSearchPolicy.candidates(all,0).map {it.id});assertEquals(all.drop(2).take(2).map {it.id},AutoMaskSearchPolicy.candidates(all,1).map {it.id});assertEquals(2,AutoMaskSearchPolicy.candidates(all,Int.MAX_VALUE).size)}
    @Test fun emptyPoolIsSafe() {assertTrue(AutoMaskSearchPolicy.candidates(emptyList(),0).isEmpty())}
    @Test fun unsupportedServerGetsNoTlsTrials() {assertTrue(AutoMaskSearchPolicy.pool(server.copy(protocol="wireguard"),"",Settings()){true}.isEmpty());assertTrue(AutoMaskSearchPolicy.pool(server.copy(security=""),"",Settings()){true}.isEmpty())}
    @Test fun automaticPoolIncludesNewTpwsProfiles() {val p=AutoMaskSearchPolicy.pool(server,"",Settings()){true};assertTrue(p.any {it.dpi=="TPWS#HELLO_RECORD"});assertTrue(p.any {it.id=="chrome.hello.fast"})}
    @Test fun missingNativeEngineExcludesItsTrials() {assertTrue(AutoMaskSearchPolicy.pool(server,"",Settings()){false}.none {it.viaByeDpi})}
    @Test fun trialsNeverChangeServerContractOrUseRandomTuning() {AutoMaskSearchPolicy.pool(server,"",Settings()){true}.forEach {assertTrue(Masks.compatible(it,server));assertTrue(it.sni.isEmpty() && it.alpn.isEmpty() && !it.hasNoise && it.mss==0 && !it.mux && !it.tfo && !it.ipv6)}}
    @Test fun poolIsDistinctAndOmitsCurrent() {val p=AutoMaskSearchPolicy.pool(server,"chrome.hello.fast",Settings()){true};assertEquals(p.size,p.map {it.id}.distinct().size);assertFalse(p.any {it.id=="chrome.hello.fast"})}
    @Test fun absentDpiPortCannotMasqueradeAsApplied() {val m=Mask("fixture","fixture","chrome",dpi="TPWS#HELLO_RECORD");assertFalse(AutoMaskSearchPolicy.engineReady(m,emptyMap()){true});assertFalse(AutoMaskSearchPolicy.engineReady(m,mapOf(m.dpi to 19090)){false});assertTrue(AutoMaskSearchPolicy.engineReady(m,mapOf(m.dpi to 19090)){true})}
    @Test fun ordinaryHelloDoesNotRequireProcess() {assertTrue(AutoMaskSearchPolicy.engineReady(Masks.byId("chrome.hello.fast")!!,emptyMap()){false})}
    private fun source(n:String)=listOf(File("src/main/java/com/vlesscardvpn/$n"),File("app/src/main/java/com/vlesscardvpn/$n")).first {it.exists()}.readText()
    @Test fun automaticMasksStartActualDpiAndCloseIt() {val s=source("core/Actions.kt").substringAfter("private suspend fun quickMaskRescue").substringBefore("private suspend fun chooseCompared");assertTrue(s.contains("dpiForTests(proposed.map"));assertTrue(s.contains("port,own.ports"));assertTrue(s.contains("finally {own.close()}"));assertTrue(s.contains("autoChecks=profile"));assertFalse(s.contains("experimentalMaskLab"))}
    @Test fun everyFailedNativeWaveCanTryMasks() {val s=source("core/Actions.kt");assertTrue(s.contains("AutoMaskSearchPolicy.candidates"));assertFalse(s.contains("round % 3 == 0"))}
    @Test fun automaticYoutubeUsesAccessNotMediaGate() {val s=source("core/Tester.kt");assertTrue(s.contains("YouTubeAccessProbe.check { boundedCall(mediaClient, it) }"));assertTrue(source("core/AutoCheckProfile.kt").contains("youtube-access-v4"))}
    @Test fun phonePredictionHasNoTunnelControl() {val s=source("core/PhoneBrain.kt");for(x in listOf("TunnelService","Store.update","maskId","Actions.cancel")) assertFalse(s.contains(x));assertTrue(source("core/PhonePower.kt").contains("PhoneBrain.reduceOptionalWork(now)"))}
}
