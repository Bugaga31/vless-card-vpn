package com.vlesscardvpn
import com.vlesscardvpn.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
class ObservationRuntimeR198Test {
 private fun snap(h:String="100",n:String="wifi",p:String="path-a")=NetworkHandoff.Snapshot(h,n,p)
 @Test fun sameKnownPathAccepted(){assertTrue(NetworkObservation.accepts(snap(),snap()))}
 @Test fun changedIpRevisionRejected(){assertFalse(NetworkObservation.accepts(snap(),snap(p="path-b")))}
 @Test fun changedHandleRejected(){assertFalse(NetworkObservation.accepts(snap(),snap(h="101")))}
 @Test fun changedProfileRejected(){assertFalse(NetworkObservation.accepts(snap(),snap(n="cell")))}
 @Test fun offlineAtStartRejected(){assertFalse(NetworkObservation.accepts(null,snap()))}
 @Test fun offlineAtCommitRejected(){assertFalse(NetworkObservation.accepts(snap(),null))}
 @Test fun bothOfflineRejected(){assertFalse(NetworkObservation.accepts(null,null))}
 @Test fun unknownInitialPathRejected(){assertFalse(NetworkObservation.accepts(snap(p=""),snap(p="")))}
 @Test fun missingCurrentPathRejected(){assertFalse(NetworkObservation.accepts(snap(),snap(p="")))}
 @Test fun blankHandleRejected(){assertFalse(NetworkObservation.accepts(snap(h=""),snap(h="")))}
 @Test fun snapshotComparisonDoesNotMutateHandoff(){val gate=NetworkHandoff();gate.seed(snap());val ticket=gate.update(snap(p="path-b"))!!;assertFalse(NetworkObservation.accepts(snap(),snap(p="path-b")));assertTrue(gate.valid(ticket.revision))}
 @Test fun canonicalPathReorderAccepted(){fun rev(a:List<String>)=NetworkPath.revision("wlan0",1500,a,listOf("default"),listOf("dns"))
 assertTrue(NetworkObservation.accepts(snap(p=rev(listOf("a","b"))),snap(p=rev(listOf("b","a","a")))))}
 @Test fun mandatoryAutoRunsWhenOptionalToggleOff(){assertTrue(BackgroundRunPolicy.enabled(true,false,false))}
 @Test fun requestedManualModeRuns(){assertTrue(BackgroundRunPolicy.enabled(false,true,false))}
 @Test fun disabledManualModeDoesNotWake(){assertFalse(BackgroundRunPolicy.enabled(false,false,false))}
 @Test fun unsupportedAutoDoesNotWake(){assertFalse(BackgroundRunPolicy.enabled(true,true,true))}
 @Test fun unsupportedManualDoesNotWake(){assertFalse(BackgroundRunPolicy.enabled(false,true,true))}
 @Test fun allFlagCombinationsMatchPolicy(){for(a in listOf(false,true))for(r in listOf(false,true))for(u in listOf(false,true))assertEquals(!u&&(a||r),BackgroundRunPolicy.enabled(a,r,u))}
 @Test fun allOrdinaryWavesPauseFiveSeconds(){for(r in listOf(-1,0,1,8,Int.MAX_VALUE))assertEquals(5000L,AutoSearchCampaign.backoff(r))}
 @Test fun persistentWaitReportsActualFiveSeconds()=runBlocking {var n=0;val waits=mutableListOf<Long>();val seen=mutableListOf<AutoSearchCampaign.WaitState>();AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},pause={waits+=it},onWait={seen+=it}) {_,_->if(++n==3) "ready" else null};assertEquals(listOf(5000L,5000L),waits);assertTrue(seen.all {it.delayMs==5000L})}
 @Test fun explicitCustomRestIsReported()=runBlocking {var n=0;val waits=mutableListOf<Long>();AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},restMs={30000},pause={waits+=it}) {_,_->if(++n==2) "ready" else null};assertEquals(listOf(30000L),waits)}
 @Test fun fiveSecondPauseRemainsCancellable()=runBlocking {val entered=CompletableDeferred<Unit>();val job=launch {AutoSearchCampaign.runPersistent<String>({"wifi"},{true},{0L},{_,_->},onWait={entered.complete(Unit)}) {_,_->null}};entered.await();job.cancelAndJoin();assertTrue(job.isCancelled)}
 @Test fun waitCopyShowsNextWaveNotEta(){val text=SearchFeedback.message(AutoSearchCampaign.WaitState(1,AutoSearchCampaign.WaitReason.BACKOFF,5000),emptyMap());assertTrue(text.contains("Следующая волна через 5 с"));assertFalse(text.contains("готово через"))}
 @Test fun noAddedOrdinaryRestOverridesFiveSeconds(){assertEquals(5000L,SearchPacingPolicy.rest(PowerPolicy.Plan()));assertEquals(5000L,AutoSearchCampaign.WAVE_PAUSE_MS)}

 @Test fun torCountryStartsUnknown(){TorExitStatus.clear();TorExitStatus.begin();assertEquals(TorExitStatus.Info(true),TorExitStatus.state.value)}
 @Test fun torCountryNormalizesIso(){val t=TorExitStatus.begin();assertTrue(TorExitStatus.publish(t,"de"));assertEquals("DE",TorExitStatus.state.value.country)}
 @Test fun torCountryRejectsFakeCode(){val t=TorExitStatus.begin();TorExitStatus.publish(t,"ZZ");assertNull(TorExitStatus.state.value.country)}
 @Test fun torCountryRejectsIp(){val t=TorExitStatus.begin();TorExitStatus.publish(t,"1.2.3.4");assertNull(TorExitStatus.state.value.country)}
 @Test fun torCountryClearRejectsLateProbe(){val t=TorExitStatus.begin();TorExitStatus.clear();assertFalse(TorExitStatus.publish(t,"US"));assertFalse(TorExitStatus.state.value.active)}
 @Test fun torCountryRestartRejectsOldCircuit(){val t=TorExitStatus.begin();val fresh=TorExitStatus.begin();assertFalse(TorExitStatus.publish(t,"NL"));assertTrue(TorExitStatus.publish(fresh,"FI"))}
 @Test fun torCountryFailureRemovesOldCountry(){val t=TorExitStatus.begin();TorExitStatus.publish(t,"DE");TorExitStatus.publish(t,null);assertTrue(TorExitStatus.label(TorExitStatus.state.value).contains("не определена"))}
 @Test fun torCountryLabelIsRussian(){assertTrue(TorExitStatus.label(TorExitStatus.Info(true,"DE")).contains("Германия"))}

 @Test fun torCountryListEmpty(){assertTrue(com.vlesscardvpn.tor.TorCountryPolicy.parse("").isEmpty())}
 @Test fun torCountryListNormalizes(){assertEquals(listOf("US","DE"),com.vlesscardvpn.tor.TorCountryPolicy.parse("us, DE,us"))}
 @Test fun torCountryListRejectsUnknown(){assertTrue(runCatching {com.vlesscardvpn.tor.TorCountryPolicy.parse("ZZ")}.isFailure)}
 @Test fun torCountryListRejectsInjection(){assertTrue(runCatching {com.vlesscardvpn.tor.TorCountryPolicy.parse("US\nSocksPort 0.0.0.0:9050")}.isFailure)}
 @Test fun torCountryConfigRestrictsExits(){val c=com.vlesscardvpn.tor.TorCountryPolicy.config("US,DE");assertTrue(c.contains("ExitNodes {us},{de}"));assertTrue(c.contains("StrictNodes 1"))}
 @Test fun torCountryAnyDoesNotPin(){assertEquals("",com.vlesscardvpn.tor.TorCountryPolicy.config(""))}
 @Test fun torRotationFiveSeconds(){assertEquals(5000L,com.vlesscardvpn.tor.TorCountryPolicy.interval(5))}
 @Test fun torRotationTenSeconds(){assertEquals(10000L,com.vlesscardvpn.tor.TorCountryPolicy.interval(10))}
 @Test fun torRotationClampsNegative(){assertEquals(5000L,com.vlesscardvpn.tor.TorCountryPolicy.interval(-1))}
 @Test fun torRotationClampsOverflow(){assertEquals(86400000L,com.vlesscardvpn.tor.TorCountryPolicy.interval(Int.MAX_VALUE))}
 @Test fun torCountriesPersist(){val s=com.vlesscardvpn.model.Settings(torExitCountries="US",torRotationSeconds=5);assertEquals("US",com.vlesscardvpn.model.Settings.fromJson(s.toJson()).torExitCountries)}
 @Test fun torFiveSecondsPersist(){val s=com.vlesscardvpn.model.Settings(torRotationSeconds=5);assertEquals(5,com.vlesscardvpn.model.Settings.fromJson(s.toJson()).torRotationSeconds)}
 @Test fun heatProtectionIsSeparate(){assertEquals(5000L,SearchPacingPolicy.rest(PowerPolicy.Plan(memory=PowerPolicy.Memory(hot=true))))}
 @Test fun callProtectionIsSeparate(){assertEquals(5000L,SearchPacingPolicy.rest(PowerPolicy.Plan(voiceCallActive=true)))}
 @Test fun mobileRecoveryCopyNamesNetwork(){assertTrue(NetworkRecoveryPresentation.message("Моб.: LTE").contains("мобильную"))}
 @Test fun wifiRecoveryDoesNotPromiseFiveSecondCompletion(){val s=NetworkRecoveryPresentation.message("Wi-Fi");assertTrue(s.contains("Wi-Fi"));assertFalse(s.contains("5"))}

 @Test fun recoveryWithoutTunnelRestartsDiscovery(){assertTrue(NetworkRecoveryPolicy.restartDiscovery(true,false,true))}
 @Test fun failedNewNetworkRecheckRestartsDiscovery(){assertTrue(NetworkRecoveryPolicy.restartDiscovery(true,true,false))}
 @Test fun provenNewNetworkRouteReconnectsDirectly(){assertFalse(NetworkRecoveryPolicy.restartDiscovery(true,true,true))}
 @Test fun manualRecoveryNeverStartsAuto(){for(t in listOf(false,true))for(p in listOf(false,true))assertFalse(NetworkRecoveryPolicy.restartDiscovery(false,t,p))}

 @Test fun bundledTorGeoDataIsPresent(){val c=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext;for(n in listOf("geoip","geoip6")) c.assets.open(n).bufferedReader().use {assertTrue(it.readLine().startsWith("#"))}}
 @Test fun nativeTorAcceptsSelectedCountryWithGeoData(){
  val c=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
  val old=Store.state.value.settings
  try { Store.update {it.copy(settings=old.copy(torExitCountries="US"))}
   val config=com.vlesscardvpn.tor.TorCard.torrcEmbedded(c)
   assertTrue(config.contains("GeoIPFile "));assertTrue(config.contains("ExitNodes {us}"))
   val f=java.io.File(c.cacheDir,"tor-country-verify198");f.writeText(config)
   val log=java.io.File(c.cacheDir,"tor-country-verify198.log")
   val process=ProcessBuilder(java.io.File(c.applicationInfo.nativeLibraryDir,"libTor.so").absolutePath,"--verify-config","-f",f.absolutePath).redirectErrorStream(true).redirectOutput(log).start()
   try {assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(log.readText().takeLast(2000),0,process.exitValue())} finally {process.destroy();f.delete();log.delete()}
  } finally {Store.update {it.copy(settings=old)}}
 }
}
