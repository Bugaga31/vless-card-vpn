package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoSearchR188Test {
 @Test fun failureStopsAfterFourWavesNotNine()=runBlocking<Unit> {
  var probes=0;var waits=0
  val r=AutoSearchCampaign.runBounded<String>({"wifi"},{true},{0L},{_,_->waits++},pause={}) {_,a->a+="s${++probes}";null}
  assertNull(r.value);assertEquals(AutoSearchCampaign.Stop.WAVE_LIMIT,r.stop);assertEquals(4,probes);assertEquals(3,waits);assertEquals(4,r.attempted)
 }
 @Test fun firstWinnerReturnsWithoutBackoff()=runBlocking<Unit> {
  val r=AutoSearchCampaign.runBounded({"wifi"},{true},{0L},{_,_->fail("wait")},pause={fail("pause")}) {_,_->"ready"}
  assertEquals("ready",r.value);assertEquals(AutoSearchCampaign.Stop.FOUND,r.stop);assertEquals(1,r.waves)
 }
 @Test fun fourthWaveStillMayWin()=runBlocking<Unit> {
  val r=AutoSearchCampaign.runBounded({"wifi"},{true},{0L},{_,_->},pause={}) {round,_->if(round==3) "ready" else null}
  assertEquals("ready",r.value);assertEquals(4,r.waves)
 }
 @Test fun elapsedBudgetStopsBeforeNextProbe()=runBlocking<Unit> {
  var now=0L;var probes=0
  val r=AutoSearchCampaign.runBounded<String>({"wifi"},{true},{now},{_,_->},pause={now=45_000}) {_,_->probes++;null}
  assertEquals(AutoSearchCampaign.Stop.TIME_LIMIT,r.stop);assertEquals(1,probes)
 }
 @Test fun hangingWaveIsCancelledAndCleanedUp()=runBlocking<Unit> {
  var cleaned=false
  val r=AutoSearchCampaign.runBounded<String>({"wifi"},{true},{0L},{_,_->},maxDurationMs=30) {_,_->try {awaitCancellation()} finally {cleaned=true}}
  assertTrue(cleaned);assertEquals(AutoSearchCampaign.Stop.TIME_LIMIT,r.stop)
 }
 @Test fun externalCancellationIsNotReportedAsBudgetFailure()=runBlocking<Unit> {
  val started=CompletableDeferred<Unit>();var returned=false
  val job=launch {AutoSearchCampaign.runBounded<String>({"wifi"},{true},{0L},{_,_->}) {_,_->started.complete(Unit);awaitCancellation()};returned=true}
  started.await();job.cancelAndJoin();assertFalse(returned)
 }
 @Test fun offlineWaitingAlsoHasTotalBudget()=runBlocking<Unit> {
  var now=0L;var probes=0
  val r=AutoSearchCampaign.runBounded<String>({"wifi"},{false},{now},{_,offline->assertTrue(offline)},pause={now+=45_000}) {_,_->probes++;null}
  assertEquals(0,probes);assertEquals(AutoSearchCampaign.Stop.TIME_LIMIT,r.stop)
 }
 @Test fun networkChurnCannotResetGlobalWaveLimit()=runBlocking<Unit> {
  var net=0;var probes=0
  val r=AutoSearchCampaign.runBounded<String>({"$net"},{true},{0L},{_,_->},pause={}) {_,a->assertTrue(a.isEmpty());a+="old";probes++;net++;null}
  assertEquals(4,probes);assertEquals(AutoSearchCampaign.Stop.WAVE_LIMIT,r.stop)
 }
 @Test fun staleNetworkWinnerIsRejected()=runBlocking<Unit> {
  var net="a";var probes=0
  val r=AutoSearchCampaign.runBounded({net},{true},{0L},{_,_->},pause={}) {_,_->if(++probes==1) {net="b";"stale"} else "fresh"}
  assertEquals("fresh",r.value)
 }
 @Test fun backwardsMonotonicClockStopsSession()=runBlocking<Unit> {
  var now=100L
  val r=AutoSearchCampaign.runBounded<String>({"wifi"},{true},{now},{_,_->},pause={now=0}) {_,_->null}
  assertEquals(AutoSearchCampaign.Stop.TIME_LIMIT,r.stop)
 }
 @Test fun attemptLedgerKeepsOnlyActuallyStartedProbeIds()=runBlocking<Unit> {
  val attempted=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  val entered=CompletableDeferred<Unit>()
  val job=launch {ProbeRace.run((0..7).toList(),1,1,probe={i->attempted+="$i";entered.complete(Unit);awaitCancellation()},onEach={_,_->})}
  entered.await();job.cancelAndJoin();assertEquals(setOf("0"),attempted)
 }
 @Test fun statisticsSeparateEveryReason() {
  val s=AutoSearchStatistics();repeat(6){s.start()}
  s.observe(false,0,null,0);s.observe(true,100,false,0);s.observe(true,100,null,0)
  s.observe(true,1001,true,100);s.observe(true,100,true,1001);s.observe(true,100,true,100)
  val c=s.snapshot();assertEquals(6,c["completed"]);assertEquals(1,c["https_unconfirmed"]);assertEquals(1,c["telegram_unconfirmed"]);assertEquals(1,c["telegram_unknown"]);assertEquals(2,c["over_1000ms"]);assertEquals(1,c["ready"])
 }
 @Test fun cancelledStartedProbeIsUnfinishedNotFailed() {
  val s=AutoSearchStatistics();s.start();val c=s.snapshot();assertEquals(1,c["unfinished"]);assertEquals(0,c["completed"]);assertEquals(0,c["https_unconfirmed"])
 }
 @Test fun fasterThanFiftyAndExactlyFiveHundredAreReady() {
  val s=AutoSearchStatistics();repeat(2){s.start()};s.observe(true,1,true,49);s.observe(true,500,true,500);assertEquals(2,s.snapshot()["ready"])
 }
 @Test fun telegramFailureIsNotMislabelledAsLatency() {
  val s=AutoSearchStatistics();s.start();s.observe(true,800,false,0);assertEquals(1,s.snapshot()["telegram_unconfirmed"]);assertEquals(0,s.snapshot()["over_1000ms"])
 }
 @Test fun unknownTelegramIsNotReadyEvenWhenHttpsFast() {
  val s=AutoSearchStatistics();s.start();s.observe(true,30,true,0);assertEquals(1,s.snapshot()["telegram_unknown"]);assertEquals(0,s.snapshot()["ready"])
 }
 @Test fun statisticsAreSafeUnderParallelCompletions() {
  val s=AutoSearchStatistics();val ts=(0..3).map {Thread {repeat(100){s.start();s.observe(true,100,true,100)}}};ts.forEach{it.start()};ts.forEach{it.join()};assertEquals(400,s.snapshot()["ready"]);assertEquals(0,s.snapshot()["unfinished"])
 }
 @Test fun diagnosticsAreFixedSchemaWithoutEndpointNames() {
  val s=AutoSearchStatistics();s.start();s.observe(true,1200,true,1200)
  val d=AutoDiagnostics.searchEvent("WAVE_LIMIT",4,s.snapshot());assertEquals(setOf("stop","waves","ordinary_probes"),d.keys().asSequence().toSet());assertEquals(1,d.getJSONObject("ordinary_probes").getInt("over_1000ms"))
 }
 @Test fun diagnosticsRejectFreeTextAndUnknownCounters() {
  try {AutoDiagnostics.searchEvent("https://private.test",1,AutoSearchStatistics().snapshot());fail("free text")} catch(_:IllegalArgumentException) {}
  try {AutoDiagnostics.searchEvent("FOUND",1,mapOf("server address" to 1));fail("unknown key")} catch(_:IllegalArgumentException) {}
 }
 @Test fun diagnosticsClampCountsAndWaves() {
  val c=AutoSearchStatistics().snapshot().mapValues {Int.MAX_VALUE};val d=AutoDiagnostics.searchEvent("TIME_LIMIT",Int.MAX_VALUE,c)
  assertEquals(100000,d.getInt("waves"));assertEquals(100000,d.getJSONObject("ordinary_probes").getInt("started"))
 }
 @Test fun defaultLimitsAreBoundedAndLatencyPolicyUnchanged() {
  assertEquals(4,AutoSearchCampaign.MAX_WAVES);assertEquals(45000L,AutoSearchCampaign.MAX_TOTAL_MS)
  assertEquals(1000,AutoLatencyPolicy.MAX_MS);assertFalse(AutoLatencyPolicy.accepts(Probe(1001,true,null,tgOk=true,tgMs=200)))
 }
}
