package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.tor.TorConnectionPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class AutoCampaignR175Test {
 @Test fun continuesUntilFourthWavePasses() = runBlocking<Unit> {
  var probes=0;val waits=mutableListOf<Int>()
  val result=AutoSearchCampaign.run<String>(network={"wifi"},online={true},elapsed={0L},waiting={r,_->waits+=r},pause={}) { _,_->probes++;if(probes==4) "data-proven" else null }
  assertEquals("data-proven",result);assertEquals(4,probes);assertEquals(listOf(0,1,2),waits)
 }
 @Test fun remembersFailedCandidatesBetweenWaves() = runBlocking<Unit> {
  AutoSearchCampaign.run<String>(network={"wifi"},online={true},elapsed={0L},waiting={_,_->},pause={}) { r, a->if(r==0) {a+="failed";null} else {assertTrue("failed" in a);"next"} }
 }
 @Test fun changedNetworkRejectsOldWinnerAndAttempts() = runBlocking<Unit> {
  var net="a";var probes=0
  val result=AutoSearchCampaign.run<String>(network={net},online={true},elapsed={0L},waiting={_,_->},pause={}) { _,a->probes++;if(probes==1) {a+="old";net="b";"stale"} else {assertTrue(a.isEmpty());"fresh"} }
  assertEquals("fresh",result);assertEquals(2,probes)
 }
 @Test fun offlineWaitsWithoutProbes() = runBlocking<Unit> {
  var online=false;var probes=0;var waits=0
  AutoSearchCampaign.run<String>(network={"wifi"},online={online},elapsed={0L},waiting={_,off->assertTrue(off);waits++},pause={assertEquals(2000L,it);online=true}) { _,_->probes++;"working" }
  assertEquals(1,waits);assertEquals(1,probes)
 }
 @Test fun cancellationStopsFurtherWaves() = runBlocking<Unit> {
  val entered=CompletableDeferred<Unit>();var probes=0
  val job=launch {AutoSearchCampaign.run<String>(network={"wifi"},online={true},elapsed={0L},waiting={_,_->},pause={entered.complete(Unit);awaitCancellation()}) { _,_->probes++;null };fail("canceled")}
  entered.await();job.cancelAndJoin();assertEquals(1,probes)
 }
 @Test fun cancellationRejectsLateResult() = runBlocking<Unit> {
  val entered=CompletableDeferred<Unit>();var returned=false
  val job=launch {AutoSearchCampaign.run<String>(network={"wifi"},online={true},elapsed={0L},waiting={_,_->}) { _,_->entered.complete(Unit);try {awaitCancellation()} catch (_:CancellationException) {"late"} };returned=true}
  entered.await();job.cancelAndJoin();assertFalse(returned)
 }
 @Test fun retriesAfterCooldownNotImmediately() = runBlocking<Unit> {
  var now=0L
  AutoSearchCampaign.run<String>(network={"wifi"},online={true},elapsed={now},waiting={_,_->},exhausted={true},pause={now=AutoSearchCampaign.RETRY_CATALOG_MS}) { r,a->if(r==0) {a+="failed";null} else {assertTrue(a.isEmpty());"retry"} }
 }
 @Test fun largeCatalogKeepsProgressAfterOneMinute() = runBlocking<Unit> {
  var now=0L
  AutoSearchCampaign.run<String>(network={"wifi"},online={true},elapsed={now},waiting={_,_->},exhausted={false},pause={now+=60_000L}) { r,a->
   if(r<3) {assertEquals(r,a.size);a+="route$r";null} else {assertEquals(3,a.size);"later route"}
  }
 }
 @Test fun boundedBackoffAvoidsBusySpin() {for(r in listOf(-1,0,1,4,100,Int.MAX_VALUE)) assertTrue(AutoSearchCampaign.backoff(r) in 1000L..5000L)}
 @Test fun rotatesThroughAllSourcesWithCloudFirst() {
  val all=listOf(Subs.CLOUD_WORKS)+(0..16).map {"https://source$it.test/sub"}
  assertEquals(all.toSet(),(0..16).flatMap {AutoDiscoveryPolicy.sources(all,false,it)}.toSet())
  for(r in 0..16) {val wave=AutoDiscoveryPolicy.sources(all,false,r);assertTrue(wave.size<=4);assertEquals(Subs.CLOUD_WORKS,wave.first())}
 }
 @Test fun torSelectionEnablesAutomaticUplinkAndRotation() {
  val old=Settings(mode=Mode.SERVERS,torBridge="obfs4",torBridgeLines="manual",torMaxLatencyMs=100);val s=TorConnectionPolicy.selectTor(old)
  assertEquals(Mode.AUTO,s.mode);assertTrue(s.torRotation);assertTrue(s.torFastOnly);assertTrue(s.torNetworkRecommendations);assertEquals(old.torBridgeLines,s.torBridgeLines);assertFalse(s.torDirect)
 }
 @Test fun migrationFixesExistingTorWithoutEnablingForOthers() {
  val s=Settings.migrate(Settings(rev=77,mode=Mode.SERVERS,torAll=true,torBridgeLines="manual"))
  assertEquals(Mode.AUTO,s.mode);assertTrue(s.torRotation);assertTrue(s.torNetworkRecommendations);assertEquals("manual",s.torBridgeLines)
  assertFalse(Settings.migrate(Settings(rev=77)).torAll);assertEquals(s,Settings.migrate(s))
 }
 @Test fun cloudCatalogDefaultsAndLegacyMigration() {assertTrue(Subs.CLOUD_WORKS in Settings().subscriptions);assertTrue(Subs.CLOUD_WORKS in Settings.migrate(Settings(rev=73,subscriptions=emptyList())).subscriptions)}
 @Test fun addedPublicSourcesAreOptInWithoutPersonalTokens() {
  val extra=Subs.CATALOG.filter { it.title.startsWith("AetrisVPN") || it.title in setOf("HavenVPN","VLESS & Co","Tarantul","catwhiteMIRROR","Mixer","bunker · BEST_LIST","bunker · WHITE_LIST") }
  assertEquals(10,extra.size);assertTrue(extra.none { it.default });assertTrue(extra.all { java.net.URI(it.url).rawQuery==null });assertTrue(extra.none { it.url.contains("p.kfwl.lol") })
 }
 @Test fun diagnosticsContainsOnlyFixedSchemaNotEndpoints() {
  val e=AutoDiagnostics.event("UNKNOWN","NO_CANDIDATE",1234)
  assertEquals(setOf("scope","status","reason","duration_ms"),e.keys().asSequence().toSet());assertEquals(1200,e.getInt("duration_ms"))
 }
 @Test fun diagnosticsRejectsFreeTextAndClampsDuration() {
  assertEquals(120000,AutoDiagnostics.event("PASS","NONE",Long.MAX_VALUE).getInt("duration_ms"))
  try {AutoDiagnostics.event("secret server address","NONE",0);fail("free text accepted")} catch (_:IllegalArgumentException) {}
 }
 @Test fun diagnosticsNetworkFamilyHasNoCarrierOrSsid() {
  assertEquals("mobile",AutoDiagnostics.family("Моб.: secret carrier"));assertEquals("other",AutoDiagnostics.family("private ssid"));assertEquals("wifi",AutoDiagnostics.family("Wi-Fi"))
 }

}
