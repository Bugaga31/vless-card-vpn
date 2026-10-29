package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.Masks
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class WarpRuntimeR191Test {
 private val net="fixture-network"
 private val warp=Server("WARP fixture","wireguard","192.0.2.1",2408,"fixture",source="warp")
 private val other=Server("saved","vless","198.51.100.1",443,"saved-key")
 private fun good()=ServerState(realMs=100,tgOk=true,tgMs=150,checkedAt=2000,serviceCheckedAt=2000,serviceNetwork=net,serviceMask="chrome.n",maskId="chrome.n")
 private fun state(p:ServerState=good())=AppState(listOf(other,warp),mapOf(other.id to ServerState(selected=true,realMs=100),warp.id to p),Settings(mode=Mode.AUTO,warpChain=true))
 private fun select(s:AppState)=WarpSetupPolicy.select(s,listOf(warp),net,1000)
 private fun unchanged(p:ServerState) {val s=state(p);assertSame(s,select(s));assertEquals(listOf(other),s.selected)}
 private fun invalid(s:String) {assertTrue(runCatching {WarpSetupPolicy.v4Address(s)}.isFailure)}
 private fun probe()=Probe(100,true,null,tgOk=true,tgMs=100,kbps=9000)
 @Test fun explicitWarpLeavesAutoAndSelectsOne() {val s=select(state());assertEquals(Mode.SERVERS,s.settings.mode);assertEquals(listOf(warp),s.selected);assertEquals(listOf(warp),AutoEligibility.launchPool(s.selected,false))}
 @Test fun explicitWarpDisablesUnmeasuredChain() {assertFalse(select(state()).settings.warpChain)}
 @Test fun noWinnerKeepsExistingSelection() {unchanged(good().copy(realMs=-1))}
 @Test fun wrongNetworkCannotReplaceSelection() {unchanged(good().copy(serviceNetwork="other"))}
 @Test fun oldCheckCannotReplaceSelection() {unchanged(good().copy(checkedAt=999))}
 @Test fun oldServiceCannotReplaceSelection() {unchanged(good().copy(serviceCheckedAt=999))}
 @Test fun wrongMaskCannotReplaceSelection() {unchanged(good().copy(serviceMask="other"))}
 @Test fun unknownTelegramCannotReplaceSelection() {unchanged(good().copy(tgOk=null))}
 @Test fun failedTelegramCannotReplaceSelection() {unchanged(good().copy(tgOk=false))}
 @Test fun slowTelegramCannotReplaceSelection() {unchanged(good().copy(tgMs=1001))}
 @Test fun slowHttpsCannotReplaceSelection() {unchanged(good().copy(realMs=1001))}
 @Test fun zeroTelegramTimingCannotReplaceSelection() {unchanged(good().copy(tgMs=0))}
 @Test fun missingCatalogueCandidateCannotReplaceSelection() {val s=state().copy(servers=listOf(other));assertSame(s,select(s))}
 @Test fun fasterThanFiftyAndBoundaryAreAllowed() {assertEquals(listOf(warp),select(state(good().copy(realMs=10,tgMs=500))).selected)}
 @Test fun unrelatedSettingsAndAccountsArePreserved() {val s=state().copy(settings=state().settings.copy(blockAds=false));val n=select(s);assertFalse(n.settings.blockAds);assertEquals(s.servers,n.servers);assertEquals(2,n.states.size)}
 @Test fun plainIpv4GetsOnePrefix() {assertEquals("172.16.0.2/32",WarpSetupPolicy.v4Address("172.16.0.2"))}
 @Test fun cidrDoesNotGetDoublePrefix() {assertEquals("172.16.0.2/32",WarpSetupPolicy.v4Address("172.16.0.2/32"))}
 @Test fun malformedIpv4Rejected() {invalid("999.1.2.3");invalid("localhost");invalid("1.2.3")}
 @Test fun repeatedPrefixRejected() {invalid("172.16.0.2/32/32")}
 @Test fun unexpectedPrefixRejected() {invalid("172.16.0.2/24")}
 @Test fun maskConfirmationRejectsSlowSecond() {assertTrue(MaskSamplePolicy.needsConfirmation(1,12,true));assertFalse(MaskSamplePolicy.needsConfirmation(0,12,true));assertFalse(MaskSamplePolicy.needsConfirmation(1,12,false));assertNull(MaskSamplePolicy.confirmed(probe(),probe().copy(tgMs=1001)))}
 @Test fun maskConfirmationRejectsSlowFirst() {assertNull(MaskSamplePolicy.confirmed(probe().copy(realMs=1001),probe()))}
 @Test fun maskConfirmationKeepsConservativeSpeed() {assertEquals(4000,MaskSamplePolicy.confirmed(probe(),probe().copy(kbps=4000))!!.kbps)}
 @Test fun latestUnknownCannotExploitPreviousSuccess() {
  val m=MaskExperimentMemory();m.schedule(net,null,"s","a",100);m.observe(net,null,"s","a",100,200,true,50)
  m.schedule(net,null,"s","a",300);m.observe(net,null,"s","a",300,400,null)
  assertEquals(listOf("b"),m.choose(net,null,"s",listOf("a","b"),16000,1));assertEquals(listOf(2,1,0,1,1),m.counts())
 }
 private suspend fun reply(body:ResponseBody,code:Int=200):WarpApi.Reply {
  val c=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()}.build()
  return try {WarpApi.request(c,Request.Builder().url("https://fixture.invalid/").build())} finally {c.dispatcher.executorService.shutdown();c.connectionPool.evictAll()}
 }
 @Test fun boundedApiReturnsStatusAndPayload()=runBlocking<Unit> {val r=reply("{}".toResponseBody());assertEquals(200,r.code);assertEquals("{}",r.text)}
 @Test fun boundedApiPreservesErrorStatus()=runBlocking<Unit> {assertEquals(429,reply("{}".toResponseBody(),429).code)}
 @Test fun declaredOversizedResponseRejected()=runBlocking<Unit> {assertTrue(runCatching {reply("x".repeat(65537).toResponseBody())}.isFailure)}
 @Test fun unknownLengthOversizedResponseRejected()=runBlocking<Unit> {
  val b=object:ResponseBody(){override fun contentType()="application/json".toMediaType();override fun contentLength()=-1L;override fun source():BufferedSource=Buffer().writeUtf8("x".repeat(65537))}
  assertTrue(runCatching {reply(b)}.isFailure)
 }
 @Test fun exactBodyLimitAccepted()=runBlocking<Unit> {assertEquals(65536,reply("x".repeat(65536).toResponseBody()).text.length)}
 @Test fun callerCancellationCancelsOwnedHttpCall()=runBlocking<Unit> {
  val entered=CompletableDeferred<Call>();val stopped=AtomicBoolean()
  val c=OkHttpClient.Builder().addInterceptor {chain->entered.complete(chain.call());while(!chain.call().isCanceled()) Thread.sleep(2);stopped.set(true);throw java.io.IOException("cancelled fixture")}.build()
  try {val j=launch {WarpApi.request(c,Request.Builder().url("https://fixture.invalid/").build())};val call=withTimeout(2000){entered.await()};j.cancelAndJoin();assertTrue(call.isCanceled());withTimeout(2000){while(!stopped.get()) delay(2)}}
  finally {c.dispatcher.executorService.shutdown();c.connectionPool.evictAll()}
 }
}
