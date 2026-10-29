package com.vlesscardvpn
import com.vlesscardvpn.core.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
class ObservationR198Test {
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
 @Test fun canonicalPathReorderAccepted(){fun rev(a:List<String>)=NetworkPath.revision("wlan0",1500,a,listOf("default"),listOf("dns"));assertTrue(NetworkObservation.accepts(snap(p=rev(listOf("a","b"))),snap(p=rev(listOf("b","a","a")))))}
 @Test fun mandatoryAutoRunsWhenOptionalToggleOff(){assertTrue(BackgroundRunPolicy.enabled(true,false,false))}
 @Test fun requestedManualModeRuns(){assertTrue(BackgroundRunPolicy.enabled(false,true,false))}
 @Test fun disabledManualModeDoesNotWake(){assertFalse(BackgroundRunPolicy.enabled(false,false,false))}
 @Test fun unsupportedAutoDoesNotWake(){assertFalse(BackgroundRunPolicy.enabled(true,true,true))}
 @Test fun unsupportedManualDoesNotWake(){assertFalse(BackgroundRunPolicy.enabled(false,true,true))}
 @Test fun allFlagCombinationsMatchPolicy(){for(a in listOf(false,true))for(r in listOf(false,true))for(u in listOf(false,true))assertEquals(!u&&(a||r),BackgroundRunPolicy.enabled(a,r,u))}
 @Test fun allOrdinaryWavesPauseFiveSeconds(){for(r in listOf(-1,0,1,8,Int.MAX_VALUE))assertEquals(5000L,AutoSearchCampaign.backoff(r))}
 @Test fun persistentWaitReportsActualFiveSeconds()=runBlocking {var n=0;val waits=mutableListOf<Long>();val seen=mutableListOf<AutoSearchCampaign.WaitState>();AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},pause={waits+=it},onWait={seen+=it}) {_,_->if(++n==3) "ready" else null};assertEquals(listOf(5000L,5000L),waits);assertTrue(seen.all {it.delayMs==5000L})}
 @Test fun hotDeviceRestIsNotBypassed()=runBlocking {var n=0;val waits=mutableListOf<Long>();AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},restMs={30000},pause={waits+=it}) {_,_->if(++n==2) "ready" else null};assertEquals(listOf(30000L),waits)}
 @Test fun fiveSecondPauseRemainsCancellable()=runBlocking {val entered=CompletableDeferred<Unit>();val job=launch {AutoSearchCampaign.runPersistent<String>({"wifi"},{true},{0L},{_,_->},onWait={entered.complete(Unit)}) {_,_->null}};entered.await();job.cancelAndJoin();assertTrue(job.isCancelled)}
 @Test fun waitCopyShowsNextWaveNotEta(){val text=SearchFeedback.message(AutoSearchCampaign.WaitState(1,AutoSearchCampaign.WaitReason.BACKOFF,5000),emptyMap());assertTrue(text.contains("Следующая волна через 5 с"));assertFalse(text.contains("готово через"))}
 @Test fun noAddedOrdinaryRestOverridesFiveSeconds(){assertEquals(0L,SearchPacingPolicy.rest(PowerPolicy.Plan()));assertEquals(5000L,AutoSearchCampaign.WAVE_PAUSE_MS)}
}
