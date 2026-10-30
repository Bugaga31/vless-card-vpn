package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NetworkRuntimeR195Test {
 private fun v4(n:Int)=InetAddress.getByAddress(byteArrayOf(127,0,0,n.toByte()))
 private fun v6(n:Int)=InetAddress.getByAddress(ByteArray(16).also {it[0]=0x20;it[1]=1;it[15]=n.toByte()})
 @Test fun cooledPrefixCannotHideDeepFreshCandidate() {val a=(0..1000).toList();assertEquals(listOf(1000),DiscoveryWindow.pool(a,{it<1000},{1}))}
 @Test fun telegramFailedPrefixCannotHideDeepCandidate() {assertEquals(listOf(1000),DiscoveryWindow.pool((0..1000).toList(),{false},{if(it<1000)2 else 1}))}
 @Test fun allCooledRetainsBoundedRecoveryInput() {val a=(0..300).toList();assertEquals(a,DiscoveryWindow.pool(a,{true},{1}))}
 @Test fun allServiceFailedRetainsRecovery() {assertEquals(listOf(1,2),DiscoveryWindow.pool(listOf(1,2),{false},{2}))}
 @Test fun neverReintroducesKnownFailureWhenPossibleExists() {assertEquals(listOf(2),DiscoveryWindow.pool(listOf(1,2),{it==2},{if(it==1)2 else 1}))}
 @Test fun stableOrderPreserved() {assertEquals(listOf(3,1),DiscoveryWindow.pool(listOf(3,2,1),{it==2},{1}))}
 @Test fun emptyPoolStaysEmpty() {assertTrue(DiscoveryWindow.pool(emptyList<Int>(),{false},{1}).isEmpty())}
 @Test fun genericPoolDoesNotInventCandidates() {assertEquals(listOf("new"),DiscoveryWindow.pool(listOf("old","new"),{it=="old"},{1}))}
 @Test fun ipv6FirstInterleavesIpv4() {val a=v6(1);val b=v6(2);val c=v4(1);assertEquals(listOf(a,c,b),DualStackOrder.order(listOf(a,b,c)))}
 @Test fun ipv4PreferenceRetained() {val a=v4(1);val b=v4(2);val c=v6(1);assertEquals(listOf(a,c,b),DualStackOrder.order(listOf(a,b,c)))}
 @Test fun duplicateAddressesRemoved() {val a=v4(1);assertEquals(listOf(a),DualStackOrder.order(listOf(a,a)))}
 @Test fun addressListBoundedAtFour() {assertEquals(4,DualStackOrder.order((1..20).map(::v4)).size)}
 @Test fun emptyAddressList() {assertTrue(DualStackOrder.order(emptyList()).isEmpty())}
 @Test fun singleFamilyOrderPreserved() {val a=(1..3).map(::v4);assertEquals(a,DualStackOrder.order(a))}
 @Test fun immediateWinnerCancelsLaterAttempts()=runBlocking {val seen=mutableListOf<Int>();val n=StaggeredConnect.hint(listOf(1,2,3),800){i,_->seen+=i;true};assertTrue(n>0);assertEquals(listOf(1),seen)}
 @Test fun brokenFirstAddressDoesNotHideAlternative()=runBlocking {val seen=mutableListOf<Int>();val n=StaggeredConnect.hint(listOf(1,2),1000){i,_->seen+=i;i==2};assertTrue(n>0);assertEquals(listOf(1,2),seen)}
 @Test fun hangingFirstAddressCancelledAfterAlternateWinner()=runBlocking {var closed=false;val n=StaggeredConnect.hint(listOf(1,2),1200){i,_->if(i==1)try {awaitCancellation()}finally {closed=true}else true};assertTrue(n>0);assertTrue(closed)}
 @Test fun sharedDeadlineNotPerAddress()=runBlocking {val begun=System.nanoTime();assertEquals(-1,StaggeredConnect.hint(listOf(1,2,3,4),100){_,_->awaitCancellation()});assertTrue((System.nanoTime()-begun)/1000000<1000)}
 @Test fun allDefiniteFailuresReturnZero()=runBlocking {assertEquals(0,StaggeredConnect.hint(listOf(1,2),1000){_,_->false})}
 @Test fun unknownResultIsNotDeadServer()=runBlocking {assertEquals(-1,StaggeredConnect.hint(listOf(1,2),1000){i,_->if(i==1)null else false})}
 @Test fun emptyHintIsUnknown()=runBlocking {assertEquals(-1,StaggeredConnect.hint(emptyList<Int>(),500){_,_->true})}
 @Test fun zeroBudgetDoesNotDial()=runBlocking {assertEquals(-1,StaggeredConnect.hint(listOf(1),0){_,_->error("must not dial")})}
 @Test fun cancellationClosesActiveAttempt()=runBlocking {val entered=CompletableDeferred<Unit>();var closed=false;val j=launch {StaggeredConnect.hint(listOf(1),10000){_,_->try {entered.complete(Unit);awaitCancellation()}finally {closed=true}}};entered.await();j.cancelAndJoin();assertTrue(closed)}
 @Test fun socketConcurrencyNeverExceedsTwo()=runBlocking {val active=java.util.concurrent.atomic.AtomicInteger();val peak=java.util.concurrent.atomic.AtomicInteger();StaggeredConnect.hint((0..10).toList(),900){_,_->val n=active.incrementAndGet();peak.updateAndGet {maxOf(it,n)};try {delay(400);false}finally{active.decrementAndGet()}};assertTrue(peak.get()<=2);assertEquals(0,active.get())}
 @Test fun dnsAnswerReturnedAndDeduplicated()=runBlocking {val a=v4(1);assertEquals(listOf(a),ProbeDns.resolve("fixture",1000){listOf(a,a)})}
 @Test fun dnsFailureUnknownNotEmptySuccess()=runBlocking {assertNull(ProbeDns.resolve("fixture",1000){error("fixture lookup failed")})}
 @Test fun dnsEmptyAnswerUnknown()=runBlocking {assertNull(ProbeDns.resolve("fixture",1000){emptyList()})}
 @Test fun dnsBlankOrNoBudgetNeverCallsResolver()=runBlocking {assertNull(ProbeDns.resolve("",100){error("called")});assertNull(ProbeDns.resolve("fixture",0){error("called")})}
 @Test fun dnsTimeoutDoesNotWaitForBlockingLookup()=runBlocking {val begun=System.nanoTime();assertNull(ProbeDns.resolve("fixture",50){Thread.sleep(2000);listOf(v4(1))});assertTrue((System.nanoTime()-begun)/1000000<1000)}
 @Test fun dnsCallerCancellationPropagates()=runBlocking {val entered=java.util.concurrent.CountDownLatch(1);var returned=false;val j=launch {ProbeDns.resolve("fixture",10000){entered.countDown();Thread.sleep(10000);listOf(v4(1))};returned=true};withContext(Dispatchers.IO){assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))};j.cancelAndJoin();assertFalse(returned)}
 @Test fun dnsAnswersBoundedAtEight()=runBlocking {assertEquals(8,ProbeDns.resolve("fixture",1000){(1..32).map(::v4)}!!.size)}
 @Test fun tcpLoopbackAlternativeActuallyConnects()=runBlocking {
  val server=java.net.ServerSocket(0,4,v4(1))
  try {val result=StaggeredConnect.hint(listOf(v4(2),v4(1)),1500){a,left->
   try {CancellableProbe.run {bind->val socket=java.net.Socket();bind {socket.close()};socket.use {it.connect(java.net.InetSocketAddress(a,server.localPort),left.toInt())};true}}catch(e:CancellationException){throw e}catch(_:Exception){false}
  };assertTrue(result>0)}finally{server.close()}
 }
 @Test fun stalledDnsHasHardWorkerBound()=runBlocking {
  val release=java.util.concurrent.CountDownLatch(1);val active=java.util.concurrent.atomic.AtomicInteger();val peak=java.util.concurrent.atomic.AtomicInteger()
  try {val jobs=(1..48).map {async {ProbeDns.resolve("fixture-$it",100){val n=active.incrementAndGet();peak.updateAndGet {maxOf(it,n)};try {while(release.count>0)try {release.await()}catch(_:InterruptedException){};listOf(v4(1))}finally{active.decrementAndGet()}}}};assertTrue(jobs.awaitAll().all {it==null});assertTrue(peak.get() in 1..4)}
  finally {release.countDown();withTimeout(3000){while(active.get()>0)delay(1)}}
 }
 @Test fun definiteHintFailureStillNeedsAuthenticatedValidation() {assertFalse(AutoLatencyPolicy.accepts(Probe(10,null,null,tgOk=null,tgMs=0)));assertFalse(AutoLatencyPolicy.accepts(Probe(10,null,null,tgOk=true,tgMs=1200)))}
}
