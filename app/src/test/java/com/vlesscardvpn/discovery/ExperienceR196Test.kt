package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ExperienceR196Test {
 @Test fun ipv4Literal() {assertArrayEquals(byteArrayOf(1,2,3,4),NumericProbeAddress.parse("1.2.3.4")!!.address)}
 @Test fun ipv4Boundary() {assertArrayEquals(byteArrayOf(0,0,0,-1),NumericProbeAddress.parse("0.0.0.255")!!.address)}
 @Test fun ipv6Compressed() {assertArrayEquals(InetAddress.getByName("2001:db8::1").address,NumericProbeAddress.parse("2001:db8::1")!!.address)}
 @Test fun ipv6Full() {assertArrayEquals(InetAddress.getByName("2001:db8:1:2:3:4:5:6").address,NumericProbeAddress.parse("2001:db8:1:2:3:4:5:6")!!.address)}
 @Test fun ipv6Unspecified() {assertEquals(16,NumericProbeAddress.parse("::")!!.address.size)}
 @Test fun ipv6TrailingCompression() {assertArrayEquals(InetAddress.getByName("2001:db8::").address,NumericProbeAddress.parse("2001:db8::")!!.address)}
 @Test fun ipv6Bracketed() {assertArrayEquals(NumericProbeAddress.parse("::1")!!.address,NumericProbeAddress.parse("[::1]")!!.address)}
 @Test fun ipv6MixedCase() {assertArrayEquals(NumericProbeAddress.parse("2001:db8::ab")!!.address,NumericProbeAddress.parse("2001:DB8::AB")!!.address)}
 @Test fun ipv6MappedV4() {assertArrayEquals(byteArrayOf(-64,0,2,1),NumericProbeAddress.parse("::ffff:192.0.2.1")!!.address)}
 @Test fun ipv6EmbeddedV4() {assertArrayEquals(InetAddress.getByName("2001:db8::192.0.2.1").address,NumericProbeAddress.parse("2001:db8::192.0.2.1")!!.address)}
 @Test fun rejectHostnames() {listOf("example.org","localhost","1.2.3.4.example").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectIpv4Shorthand() {listOf("127.1","2130706433","0x7f000001","1.2.3").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectAmbiguousLeadingZeros() {listOf("01.2.3.4","001.0.0.1").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectOutOfRange() {listOf("256.1.2.3","1.2.3.-1","1.2.3.1000").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectWhitespaceAndBrackets() {listOf(" ::1","::1 ","[::1","::1]","[::1]:443", "[127.0.0.1]").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectScopesWithoutDiscardingZone() {assertNull(NumericProbeAddress.parse("fe80::1%wlan0"));assertNull(NumericProbeAddress.parse("fe80::1%2"))}
 @Test fun rejectMalformedIpv6() {listOf(":::1","1::2::3",":1:2:3:4:5:6:7","1:2:3:4:5:6:7:","2001:db8:g::1").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectWrongWordCounts() {listOf("1:2:3:4:5:6:7","1:2:3:4:5:6:7:8:9","1:2:3:4:5:6:7:8::").forEach {assertNull(NumericProbeAddress.parse(it))}}
 @Test fun rejectEmbeddedIpv4NotAtEnd() {assertNull(NumericProbeAddress.parse("192.0.2.1::"));assertNull(NumericProbeAddress.parse("::192.0.2.1:2"))}
 @Test fun boundedInput() {assertNull(NumericProbeAddress.parse("1".repeat(10000)));assertNull(NumericProbeAddress.parse(""))}
 @Test fun literalDoesNotCallDns()=runBlocking {assertEquals("127.0.0.1",ProbeDns.resolve("127.0.0.1",500){error("DNS must not be used")}!!.single().hostAddress)}
 @Test fun ipv6LiteralDoesNotCallDns()=runBlocking {assertEquals(16,ProbeDns.resolve("2001:db8::1",500){error("DNS must not be used")}!!.single().address.size)}
 @Test fun literalHonoursNoBudget()=runBlocking {assertNull(ProbeDns.resolve("127.0.0.1",0){error("no budget")})}
 @Test fun literalSurvivesSaturatedDnsWorkers()=runBlocking {
  val release=java.util.concurrent.CountDownLatch(1);val entered=java.util.concurrent.CountDownLatch(4)
  val jobs=(1..4).map {async(Dispatchers.Default){ProbeDns.resolve("stall-$it",5000){entered.countDown();while(release.count>0)try {release.await()}catch(_:InterruptedException){};emptyList()}}}
  try {withContext(Dispatchers.IO){assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))};withTimeout(500){assertNotNull(ProbeDns.resolve("192.0.2.1",200){error("queued DNS")});assertNotNull(ProbeDns.resolve("::1",200){error("queued DNS")})}}
  finally {release.countDown();jobs.awaitAll()}
 }
 @Test fun offlineWaitReason()=runBlocking {var online=false;val seen=mutableListOf<AutoSearchCampaign.WaitState>();AutoSearchCampaign.runPersistent({"a"},{online},{0L},{_,_->},pause={online=true},onWait={seen+=it}) {_,_->"ok"};assertEquals(AutoSearchCampaign.WaitReason.OFFLINE,seen.single().reason);assertEquals(2000L,seen.single().delayMs)}
 @Test fun backoffReasonReportsActualPause()=runBlocking {var n=0;val seen=mutableListOf<AutoSearchCampaign.WaitState>();AutoSearchCampaign.runPersistent({"a"},{true},{0L},{_,_->},restMs={30000},pause={},onWait={seen+=it}) {_,_->if(++n==2)"ok" else null};assertEquals(AutoSearchCampaign.WaitReason.BACKOFF,seen.single().reason);assertEquals(30000L,seen.single().delayMs)}
 @Test fun exhaustedReasonDoesNotStartMoreProbes()=runBlocking {var now=0L;var n=0;val seen=mutableListOf<AutoSearchCampaign.WaitState>();AutoSearchCampaign.runPersistent({"a"},{true},{now},{_,_->},exhausted={it.isNotEmpty()},pause={now+=it},onWait={seen+=it}) {_,ids->ids+="one";if(++n==2)"ok" else null};assertEquals(2,n);assertTrue(seen.any {it.reason==AutoSearchCampaign.WaitReason.CATALOG_RETRY});assertTrue(now>=60000)}
 @Test fun feedbackUsesMeasuredCounts() {val text=SearchFeedback.message(AutoSearchCampaign.WaitState(8,AutoSearchCampaign.WaitReason.BACKOFF,5000),mapOf("completed" to 17,"unfinished" to 3));assertTrue(text.contains("17"));assertTrue(text.contains("3"));assertTrue(text.contains("9"));assertTrue(text.contains("5 с"));assertFalse(text.contains("%"))}
 @Test fun waitMessagesDoNotClaimWorkingRoute() {AutoSearchCampaign.WaitReason.values().forEach {val s=AutoSearchCampaign.WaitState(0,it,5000);assertTrue(SearchFeedback.title(s).startsWith("Авто:"));assertFalse(SearchFeedback.message(s,emptyMap()).contains("Маршрут подключён"))}}
 @Test fun noSecondProgressWhileConnecting() {assertFalse(SearchFeedback.secondaryProgress(true));assertTrue(SearchFeedback.secondaryProgress(false))}
 @Test fun noPlaceholderWhenStageMessageKnown() {assertNull(SearchFeedback.counter(0,0,"Ждём сеть"));assertEquals("Подготавливаем проверку",SearchFeedback.counter(0,0,""))}
 @Test fun finiteCounterClamped() {assertEquals("8 из 8",SearchFeedback.counter(99,8,"проверка"));assertEquals("0 из 8",SearchFeedback.counter(-2,8,""))}
 @Test fun diagnosticsPreservesTwelveWaves() {assertEquals(12,AutoDiagnostics.searchEvent("FOUND",12,AutoSearchStatistics().snapshot()).getInt("waves"))}
 @Test fun diagnosticsStillBounded() {assertEquals(100000,AutoDiagnostics.searchEvent("FOUND",Int.MAX_VALUE,AutoSearchStatistics().snapshot()).getInt("waves"))}
 @Test fun cancellationDuringReportedWait()=runBlocking {val entered=CompletableDeferred<Unit>();val j=launch {AutoSearchCampaign.runPersistent<String>({"a"},{false},{0L},{_,_->},onWait={entered.complete(Unit)}) {_,_->error("offline")}};entered.await();j.cancelAndJoin();assertTrue(j.isCancelled)}
 @Test fun waitRoundDoesNotOverflowPresentation() {assertTrue(SearchFeedback.message(AutoSearchCampaign.WaitState(Int.MAX_VALUE,AutoSearchCampaign.WaitReason.BACKOFF,1000),emptyMap()).contains("2147483648"))}
}
