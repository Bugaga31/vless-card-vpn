package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class DiscoveryAdmissionRuntimeR202Test {
 private fun ready(n:String)=Server(n,"vless","$n.test",443,"key",security="tls",source=n)
 private fun pending(n:String)=ready(n).copy(insecure=true)
 @Test fun verifiedTlsDoesNotNeedAcquisition(){assertFalse(ForegroundProbePlan.needsCertificate(ready("a")))}
 @Test fun unpinnedSelfSignedNeedsAcquisition(){assertTrue(ForegroundProbePlan.needsCertificate(pending("a")))}
 @Test fun existingPinIsReady(){assertFalse(ForegroundProbePlan.needsCertificate(pending("a").copy(pcs="sha")))}
 @Test fun realityDoesNotUseTlsAcquisition(){assertFalse(ForegroundProbePlan.needsCertificate(pending("a").copy(security="reality")))}
 @Test fun plainTransportDoesNotAcquireCertificate(){assertFalse(ForegroundProbePlan.needsCertificate(pending("a").copy(security="")))}
 @Test fun quicSelfSignedNeedsAcquisition(){assertTrue(ForegroundProbePlan.needsCertificate(pending("a").copy(protocol="hysteria2",security="")))}
 @Test fun verifiedQuicDoesNotAcquireCertificate(){assertFalse(ForegroundProbePlan.needsCertificate(ready("a").copy(protocol="hysteria2")))}
 @Test fun emptyQueueHasNoCohorts(){assertTrue(ForegroundProbePlan.cohorts(emptyList(),true).isEmpty())}
 @Test fun readyRoutesPrecedePending(){val p=pending("p");val r=ready("r");assertEquals(listOf(listOf(r),listOf(p)),ForegroundProbePlan.cohorts(listOf(p,r),true))}
 @Test fun stableOrderWithinCohorts(){val p=pending("p");val q=pending("q");val r=ready("r");val s=ready("s");assertEquals(listOf(listOf(r,s),listOf(p,q)),ForegroundProbePlan.cohorts(listOf(p,r,q,s),true))}
 @Test fun allPendingStillProbed(){val p=listOf(pending("p"),pending("q"));assertEquals(listOf(p),ForegroundProbePlan.cohorts(p,true))}
 @Test fun allReadyRemainOneCoreBatch(){val r=listOf(ready("r"),ready("s"));assertEquals(listOf(r),ForegroundProbePlan.cohorts(r,true))}
 @Test fun backgroundOrderIsUnchanged(){val q=listOf(pending("p"),ready("r"));assertEquals(listOf(q),ForegroundProbePlan.cohorts(q,false))}
 @Test fun admittedQuotaAndEndpointSetAreUnchanged(){val q=(1..8).map {if(it%2==0) ready("r$it") else pending("p$it")};val out=ForegroundProbePlan.cohorts(q,true).flatten();assertEquals(8,out.size);assertEquals(q.toSet(),out.toSet());assertEquals(q.groupingBy{it.source}.eachCount(),out.groupingBy{it.source}.eachCount())}
 @Test fun readyWinnerNeverWaitsForAcquisition()=runBlocking {val calls=mutableListOf<List<Server>>();val r=ready("r");assertEquals(1,ForegroundProbePlan.run(listOf(pending("p"),r),true,{true}) {calls+=it;assertFalse(it.any(ForegroundProbePlan::needsCertificate));1});assertEquals(listOf(listOf(r)),calls)}
 @Test fun readyFailureContinuesToPending()=runBlocking {val p=pending("p");val r=ready("r");val calls=mutableListOf<List<Server>>();assertEquals(1,ForegroundProbePlan.run(listOf(p,r),true,{true}) {calls+=it;if(it==listOf(p))1 else 0});assertEquals(listOf(listOf(r),listOf(p)),calls)}
 @Test fun noSuccessIsInvented()=runBlocking {assertEquals(0,ForegroundProbePlan.run(listOf(pending("p"),ready("r")),true,{true}) {0})}
 @Test fun staleNetworkPreventsEveryCohort()=runBlocking {assertEquals(0,ForegroundProbePlan.run(listOf(pending("p"),ready("r")),true,{false}) {error("stale probe")})}
 @Test fun networkChangePreventsPendingPreparation()=runBlocking {var valid=true;var count=0;assertEquals(0,ForegroundProbePlan.run(listOf(pending("p"),ready("r")),true,{valid}) {count++;valid=false;0});assertEquals(1,count)}
 @Test fun cancellationDoesNotEnterDeferredCohort()=runBlocking {var calls=0;try {ForegroundProbePlan.run(listOf(pending("p"),ready("r")),true,{true}) {calls++;throw CancellationException("stop")};fail("must cancel")}catch(_:CancellationException){};assertEquals(1,calls)}
 @Test fun foregroundHasNoArtificialWorkerStagger(){assertEquals(0L,ForegroundProbePlan.staggerMs(true,1))}
 @Test fun backgroundHedgingIsRetained(){assertEquals(120L,ForegroundProbePlan.staggerMs(false,1))}
 @Test fun completeBatchNeverStaggers(){assertEquals(0L,ForegroundProbePlan.staggerMs(false,Int.MAX_VALUE));assertEquals(0L,ForegroundProbePlan.staggerMs(true,Int.MAX_VALUE))}
 @Test fun admittedWorkersCanStartTogetherWithoutIncreasingSlots()=runBlocking {val gate=CompletableDeferred<Unit>();val admitted=CompletableDeferred<Unit>();val starts=java.util.concurrent.atomic.AtomicInteger();val j=launch {ProbeRace.run((0..3).toList(),4,1,staggerMs=ForegroundProbePlan.staggerMs(true,1),probe={if(starts.incrementAndGet()==4)admitted.complete(Unit);gate.await();Probe(0,null,null,"fixture")},onEach={_,_->})};withTimeout(2000){admitted.await()};assertEquals(4,starts.get());gate.complete(Unit);j.join()}
}
