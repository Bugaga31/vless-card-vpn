package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FastAutoBudgetR149Test {
    private val now=1_000_000L
    private fun route(i:Int)=Server("route-$i","vless","host-$i.invalid",443,"fixture-$i")
    private fun base(speed:Int=4000,ping:Int=20)=ServerState(realMs=ping,bigOk=true,httpsHosts=2,httpsNetwork="wifi",httpsCheckedAt=now,
        kbps=speed,speedNetwork="wifi",speedCheckedAt=now)
    private fun proven(speed:Int=4000,ping:Int=20)=base(speed,ping).copy(serviceNetwork="wifi",serviceCheckedAt=now,serviceTgOk=true,serviceYtOk=true)

    @Test fun oneMonotonicDeadlineIncludesQueueStartupAndRetries() {
        var elapsed=100L
        val attempt=AutoForegroundAttempt(1,"wifi","handle",{elapsed})
        assertEquals(15000L,attempt.remainingMs())
        elapsed+=3000 // waiting for mutex/executor
        assertEquals(12000L,attempt.remainingMs())
        elapsed+=2500 // native startup
        assertEquals(9500L,attempt.remainingMs())
        elapsed+=3500 // phase change/retry does not reset the clock
        assertEquals(1000L,attempt.searchMs())
        elapsed+=6000
        assertEquals(0L,attempt.remainingMs());assertEquals("budget-exhausted",attempt.reason())
    }
    @Test fun exhaustedBudgetCannotRunNewMaskOrDpiPhase()=runBlocking {
        var clock=0L;var started=false
        val a=AutoForegroundAttempt(1,"wifi","h",{clock});clock=15000
        try { a.within { started=true };fail("expired attempt accepted work") } catch (_:AutoBudgetExpired) {}
        assertFalse(started)
    }
    @Test fun allServiceUnknownAllowsOneHonestBestDataFallback() {
        val a=route(1);val b=route(2);val states=mapOf(a.id to base(1000,5),b.id to base(9000,90))
        val pool=AutoEligibility.connectionPool(listOf(a,b),{states.getValue(it.id)},"wifi",now)
        assertEquals(listOf(b),pool)
        assertTrue(AutoEligibility.servicePool(pool,{states.getValue(it.id)},"wifi",now).isEmpty())
        assertNull(states.getValue(b.id).serviceYtOk)
    }
    @Test fun provenPoolNeverMixedWithUnknownEvenWhenUnknownIsFaster() {
        val a=route(1);val b=route(2);val states=mapOf(a.id to proven(1000),b.id to base(9000))
        assertEquals(listOf(a),AutoEligibility.connectionPool(listOf(a,b),{states.getValue(it.id)},"wifi",now))
    }
    @Test fun allConfirmedFailuresCannotBecomeFallback() {
        val all=(0..4).map(::route);val failed=base().copy(serviceNetwork="wifi",serviceCheckedAt=now,serviceTgOk=false)
        assertTrue(AutoEligibility.connectionPool(all,{failed},"wifi",now).isEmpty())
        assertFalse(AutoEligibility.connectionWorks(Probe(10,true,null,tgOk=false,httpsHosts=2,httpsChecked=true)))
    }
    @Test fun unavailableCommonEndpointDoesNotTeachFailureOrInventPass() {
        val p=Probe(0,null,null,httpsHosts=0,httpsChecked=true)
        assertNull(AutoEligibility.connectionVerdict(p));assertFalse(AutoEligibility.connectionWorks(p))
        assertTrue(AutoEligibility.connectionPool(listOf(route(0)),{base().copy(httpsHosts=0)},"wifi",now).isEmpty())
    }
    @Test fun youtubeDiscoveryUnknownIsNotServiceFail() {
        val p=Probe(20,true,null,tgOk=true,ytReason="player-login-required",httpsHosts=2,httpsChecked=true)
        assertTrue(AutoEligibility.connectionWorks(p));assertFalse(AutoEligibility.servicesWork(p))
        assertNull(AutoEligibility.learningVerdict(p,true))
    }
    @Test fun firstHttpsPassWithTelegramFailDoesNotStopAdmissionSearch() {
        val p=Probe(20,true,null,tgOk=false,httpsHosts=2,httpsChecked=true)
        val q=p.copy(tgOk=true,ytOk=true)
        assertEquals(q,listOf(p,q).firstOrNull(AutoEligibility::connectionWorks))
    }
    @Test fun measuredPerformanceWinsOverLowPingAmongConfirmed() {
        val a=route(1);val b=route(2);val states=mapOf(a.id to proven(500,2),b.id to proven(9000,100))
        assertEquals(b,AutoEligibility.connectedPool(listOf(a,b),{states.getValue(it.id)},"wifi",now,false).first())
    }
    @Test fun cancellationClosesAttemptAndCannotProduceLateSuccess()=runBlocking {
        val a=AutoForegroundAttempt(1,"wifi","h",{0L})
        try { a.within { a.close("cancelled");42 };fail("late success") } catch (_:CancellationException) {}
        assertFalse(a.valid("wifi","h"));assertEquals("cancelled",a.reason())
    }
    @Test fun successfulServiceHandoffCanFinishBeforeSearchBookkeeping()=runBlocking {
        val a=AutoForegroundAttempt(1,"wifi","h",{0L})
        assertEquals(42,a.within { a.close("connected");42 })
        assertFalse(a.valid("wifi","h")) // cannot perform another handoff
    }
    @Test fun oldNetworkHandleAndOldGenerationCannotBecomeFresh() {
        val a=AutoForegroundAttempt(1,"wifi","h1",{0L})
        assertFalse(a.valid("wifi","h2"));assertFalse(a.valid("cell","h1"))
        a.close("stale-network-result")
        val b=AutoForegroundAttempt(2,"wifi","h1",{0L})
        assertFalse(a.valid("wifi","h1"));assertTrue(b.valid("wifi","h1"))
        assertTrue(AutoEligibility.connectionPool(listOf(route(0)),{proven()},"cell",now).isEmpty())
    }
    @Test fun foregroundCandidateLimitDoesNotTruncateFullBackgroundCatalogue() {
        val all=(0 until 1200).map(::route)
        val wave=PublicCloudQueue.merge(listOf(all[900]),listOf(all[1000]),all,AutoForegroundAttempt.MAX_CANDIDATES)
        assertEquals(8,wave.size);assertTrue(all[900] in wave);assertTrue(all[1000] in wave)
        assertEquals(1200,FullCatalogChecks.waves(FullCatalogChecks.ordered(all,emptyMap())).flatten().size)
    }
    @Test fun staleCloudOnlyChangesQueueOrderNeverCreatesProof() {
        val all=(0..10).map(::route)
        val wave=PublicCloudQueue.merge(listOf(all[0]),emptyList(),all,8)
        assertEquals(all[0],wave.first())
        assertTrue(AutoEligibility.connectionPool(wave,{ServerState()},"wifi",now).isEmpty())
    }
    @Test fun oldMaskCannotConfirmNewStrategyAndHistoryIsNotDeleted() {
        val old=proven().copy(maskId="chrome.h4",okCount=20,failCount=3)
        assertFalse(AutoEligibility.httpsFresh(old,"wifi",now));assertEquals(20,old.okCount);assertEquals(3,old.failCount)
    }
    @Test fun backgroundConfirmedFailureExcludesTheSingleFallback() {
        val p=Probe(20,true,null,tgOk=false,httpsHosts=2,httpsChecked=true)
        val updated=AutoEligibility.recordChainServices(base(),"wifi","",now,now+10,p,true)
        assertEquals(2,AutoEligibility.serviceRank(updated,"wifi",now+10))
        assertTrue(AutoEligibility.connectionPool(listOf(route(0)),{updated},"wifi",now+10).isEmpty())
    }
    @Test fun sharedBalancerFailureRevokesProofWithoutInventingMemberFailure() {
        val s=proven().copy(okCount=15,failCount=2)
        val p=Probe(20,true,null,tgOk=false,httpsHosts=2,httpsChecked=true)
        val updated=AutoEligibility.recordChainServices(s,"wifi","",now,now+10,p,false)
        assertEquals(0L,updated.serviceCheckedAt);assertEquals(2,updated.failCount);assertEquals(15,updated.okCount)
        assertEquals(true,updated.serviceTgOk) // old boolean retained as history, not usable current proof
        assertEquals(1,AutoEligibility.serviceRank(updated,"wifi",now+10))
    }
    @Test fun newerReceiptOrNewMaskCannotBeOverwrittenByLateBackgroundFailure() {
        val p=Probe(20,true,null,tgOk=false,httpsHosts=2,httpsChecked=true)
        val newer=proven().copy(serviceCheckedAt=now+20)
        assertEquals(newer,AutoEligibility.recordChainServices(newer,"wifi","",now,now+10,p,true))
        val changed=base().copy(maskId="chrome.h4")
        assertEquals(changed,AutoEligibility.recordChainServices(changed,"wifi","",now,now+10,p,true))
    }
    @Test fun resultReasonsCannotContainSecrets() {
        try { AutoDiagnostics.record(1,"search",AutoDiagnostics.Verdict.UNKNOWN,"vless://secret");fail("unsafe reason") }
        catch (_:IllegalArgumentException) {}
    }
}
