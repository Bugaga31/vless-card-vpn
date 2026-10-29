package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProductRuntimeR194Test {
 @Test fun beyondFourWaves()=runBlocking {var n=0;val r=AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},pause={}) {_,_->if(++n==12) "ready" else null};assertEquals(12,r.waves);assertEquals("ready",r.value)}
 @Test fun beyond45Seconds()=runBlocking {var now=0L;var n=0;val r=AutoSearchCampaign.runPersistent({"wifi"},{true},{now},{_,_->},pause={now+=it}) {_,_->now+=20000;if(++n==5) "ready" else null};assertTrue(now>45000);assertEquals(5,r.waves)}
 @Test fun exhaustedCatalogWaitsBeforeRetry()=runBlocking {var now=0L;var n=0;var pauses=0;AutoSearchCampaign.runPersistent({"a"},{true},{now},{_,_->},exhausted={"one" in it},pause={now+=it;pauses++}) {_,ids->if(++n==2){assertTrue(now>=60000);assertTrue(ids.isEmpty());"ok"}else{ids+="one";null}};assertTrue(pauses>=12);assertEquals(2,n)}
 @Test fun newCandidateResumesBeforeMinute()=runBlocking {var now=0L;var n=0;AutoSearchCampaign.runPersistent({"a"},{true},{now},{_,_->},exhausted={now<5000 && it.isNotEmpty()},pause={now+=it}) {_,ids->ids+="one";if(++n==2) "ok" else null};assertTrue(now<60000)}
 @Test fun networkChangeResetsAttempts()=runBlocking {var net="a";var n=0;AutoSearchCampaign.runPersistent({net},{true},{0L},{_,_->},pause={}) {_,ids->assertTrue(ids.isEmpty());ids+="old";if(++n==1){net="b";null}else "ok"};assertEquals(2,n)}
 @Test fun staleWinnerNotReturned()=runBlocking {var net="a";var n=0;val r=AutoSearchCampaign.runPersistent({net},{true},{0L},{_,_->},pause={}) {_,_->if(++n==1){net="b";"stale"}else "fresh"};assertEquals("fresh",r.value)}
 @Test fun offlineDoesNotProbe()=runBlocking {var now=0L;var n=0;AutoSearchCampaign.runPersistent({"a"},{now>=4000},{now},{_,offline->assertTrue(offline)},pause={now+=it}) {_,_->n++;"ok"};assertEquals(1,n);assertEquals(4000L,now)}
 @Test fun cancellationInterruptsOfflineWait()=runBlocking {val entered=CompletableDeferred<Unit>();var returned=false;val j=launch {AutoSearchCampaign.runPersistent<String>({"a"},{false},{0L},{_,_->entered.complete(Unit)}) {_,_->error("offline")};returned=true};entered.await();j.cancelAndJoin();assertFalse(returned)}
 @Test fun cancellationClosesWave()=runBlocking {val entered=CompletableDeferred<Unit>();var cleaned=false;val j=launch {AutoSearchCampaign.runPersistent<String>({"a"},{true},{0L},{_,_->}) {_,_->try {entered.complete(Unit);awaitCancellation()}finally{cleaned=true}}};entered.await();j.cancelAndJoin();assertTrue(cleaned)}
 @Test fun hotPauseBetweenWaves()=runBlocking {val waits=mutableListOf<Long>();var n=0;AutoSearchCampaign.runPersistent({"a"},{true},{0L},{_,_->},restMs={30000L},pause={waits+=it}) {_,_->if(++n==2) "ok" else null};assertEquals(listOf(30000L),waits)}
 @Test fun sameRouteNeverRestarts() {assertFalse(BoostDecision.shouldReplace(true,"a","a",10,100000))}
 @Test fun unknownBaselineNeverRestarts() {assertFalse(BoostDecision.shouldReplace(true,"a","b",0,100000))}
 @Test fun noCurrentLiveRouteNeverRestarts() {assertFalse(BoostDecision.shouldReplace(true,null,"b",100,100000))}
 @Test fun zeroCandidateRejected() {assertFalse(BoostDecision.shouldReplace(false,"a","b",100,0))}
 @Test fun negativeCandidateRejected() {assertFalse(BoostDecision.shouldReplace(false,"a","b",100,-1))}
 @Test fun marginalGainPreservesRoute() {assertFalse(BoostDecision.shouldReplace(true,"a","b",10000,13000))}
 @Test fun exactThresholdPreservesRoute() {assertFalse(BoostDecision.shouldReplace(true,"a","b",10000,13500))}
 @Test fun measuredGainAllowsSwitch() {assertTrue(BoostDecision.shouldReplace(true,"a","b",10000,13501))}
 @Test fun disconnectedCanSelectMeasuredWinner() {assertTrue(BoostDecision.shouldReplace(false,null,"b",0,1000))}
 @Test fun comparisonDoesNotOverflow() {assertFalse(BoostDecision.shouldReplace(true,"a","b",Int.MAX_VALUE,Int.MAX_VALUE))}
 @Test fun hotPaces30s() {assertEquals(30000L,SearchPacingPolicy.rest(PowerPolicy.Plan(memory=PowerPolicy.Memory(hot=true))))}
 @Test fun callPaces15s() {assertEquals(15000L,SearchPacingPolicy.rest(PowerPolicy.Plan(voiceCallActive=true)))}
 @Test fun darkScreenPaces15s() {assertEquals(15000L,SearchPacingPolicy.rest(PowerPolicy.Plan(screenInteractive=false)))}
 @Test fun batteryPressurePaces5s() {assertEquals(5000L,SearchPacingPolicy.rest(PowerPolicy.Plan(allowLight=false)))}
 @Test fun normalPlanNoAddedDelay() {assertEquals(0L,SearchPacingPolicy.rest(PowerPolicy.Plan()))}
 @Test fun screenOffCapsForeground() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,screen=false),PowerPolicy.Options());assertEquals(4,p.foregroundRealParallel);assertEquals(4,p.foregroundTcpParallel)}
 @Test fun saverCapsForeground() {assertEquals(4,PowerPolicy.evaluate(PowerPolicy.Input(0,saver=true),PowerPolicy.Options()).foregroundRealParallel)}
 @Test fun ecoCapsForeground() {assertEquals(4,PowerPolicy.evaluate(PowerPolicy.Input(0),PowerPolicy.Options(profile="eco")).foregroundRealParallel)}
 @Test fun heatStillStricter() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,thermal=3,screen=false),PowerPolicy.Options());assertEquals(2,p.foregroundRealParallel);assertFalse(p.allowLight)}
 @Test fun callsStillSingleSlot() {assertEquals(1,PowerPolicy.evaluate(PowerPolicy.Input(0,voiceCall=true,screen=false),PowerPolicy.Options()).foregroundRealParallel)}
}
