package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FastConnectR211Test {
    private val profile=AutoCheckProfile.of(AutoCheckProfile.allowed)
    private val probe=Probe(1800,null,true,tgOk=true,tgMs=1600,callsOk=true,discordOk=true,stableOk=true)
    private val server=Server("fixture","vless","192.0.2.1",443,"fixture")
    private val path=NetworkHandoff.Snapshot("handle","wifi","revision")
    private fun state()=ServerState(realMs=1800,tgOk=true,tgMs=1600,ytOk=true,checkedAt=100,
        autoChecksKey=AutoChecks.key(profile,Int.MAX_VALUE),autoChecksAt=100,serviceNetwork="wifi",serviceMask="",serviceCheckedAt=100)
    private fun offer()=SlowRouteConsent.Offer(server,path,"",profile,1000,"https://example.test",100,1800)
    @Test fun slowProofIsNotStrictAdmission() {val key=AutoChecks.stamp(probe,profile,1000);assertEquals(AutoChecks.key(profile,Int.MAX_VALUE),key);assertFalse(AutoChecks.fresh(key,profile,1000,100,101))}
    @Test fun eachSelectedServiceStillMandatory() {for(p in listOf(probe.copy(tgOk=false),probe.copy(tgOk=null),probe.copy(ytOk=false),probe.copy(discordOk=false),probe.copy(discordOk=null),probe.copy(stableOk=false),probe.copy(callsOk=false),probe.copy(realMs=0))) assertEquals("",AutoChecks.stamp(p,profile,1000))}
    @Test fun slowCandidateRequiresFreshSelectedProof() {assertTrue(SlowRoutePolicy.measured(state(),profile,1000,"wifi",101));assertFalse(SlowRoutePolicy.measured(state().copy(autoChecksKey=""),profile,1000,"wifi",101))}
    @Test fun oldAndFutureAndOtherNetworkCandidatesRejected() {for(now in listOf(99L,300101L)) assertFalse(SlowRoutePolicy.measured(state(),profile,1000,"wifi",now));assertFalse(SlowRoutePolicy.measured(state(),profile,1000,"cell",101))}
    @Test fun unknownMaskCannotReuseOffer() {assertFalse(SlowRoutePolicy.measured(state().copy(maskId="new"),profile,1000,"wifi",101))}
    @Test fun fastCandidateDoesNotNeedConsent() {assertFalse(SlowRoutePolicy.measured(state().copy(realMs=50,tgMs=60),profile,1000,"wifi",101))}
    @Test fun consentBindsExactServerAndPath() {val o=offer();assertTrue(SlowRoutePolicy.matches(o,server,path,profile,1000,o.url,""));assertFalse(SlowRoutePolicy.matches(o,server.copy(port=444),path,profile,1000,o.url,""));assertFalse(SlowRoutePolicy.matches(o,server,path.copy(handle="new"),profile,1000,o.url,""));assertFalse(SlowRoutePolicy.matches(o,server,path.copy(path="new"),profile,1000,o.url,""))}
    @Test fun settingsChangesInvalidateConsent() {val o=offer();assertFalse(SlowRoutePolicy.matches(o,server,path,AutoCheckProfile.of(emptyList()),1000,o.url,""));assertFalse(SlowRoutePolicy.matches(o,server,path,profile,800,o.url,""));assertFalse(SlowRoutePolicy.matches(o,server,path,profile,1000,"https://other.test",""));assertFalse(SlowRoutePolicy.matches(o,server,path,profile,1000,o.url,"new"))}
    @Test fun noGrantCannotAdmitSlowRoute() {SlowRouteConsent.clear();assertFalse(SlowRouteConsent.permits(server,state(),"wifi",101))}
    @Test fun knownWorkingRouteGetsSlotAheadOfCloudCatalog() {val primary=(1..1000).map {"cloud$it"};assertEquals("local",KnownRouteQueue.merge(primary,listOf("local"),20){it}.first())}
    @Test fun knownQueueIsBoundedAndDeduplicated() {val q=KnownRouteQueue.merge(listOf("a","a","b"),listOf("a","c","c"),3){it};assertEquals(3,q.size);assertEquals(q.distinct(),q);assertTrue(KnownRouteQueue.merge(listOf("a"),listOf("b"),0){it}.isEmpty())}
    @Test fun oldFailureAndCooldownAreNotPermanentRemoval() {assertEquals(setOf("bad","ready","cold"),DiscoveryWindow.pool(listOf("bad","ready","cold"),{it=="cold"},{if(it=="bad")2 else 1}).toSet())}
    @Test fun powerPoliciesDoNotReduceExplicitTen() {for(i in listOf(PowerPolicy.Input(1,thermal=5),PowerPolicy.Input(1,lowMemory=true),PowerPolicy.Input(1,voiceCall=true),PowerPolicy.Input(1,saver=true),PowerPolicy.Input(1,screen=false))) for(fg in listOf(false,true)) assertEquals(10,ProbeConcurrencyPolicy.limit(20,10,PowerPolicy.evaluate(i,PowerPolicy.Options()),fg))}
    @Test fun reserveDiscoveryAllowedWhileAutoIsConnectedEvenHot() {assertTrue(BackgroundReservePolicy.mayProbe(true,PowerPolicy.evaluate(PowerPolicy.Input(1,thermal=5,voiceCall=true),PowerPolicy.Options())))}
    @Test fun noStartupStaggerAndOneSecondWavePause() {for(fg in listOf(false,true))assertEquals(0L,ForegroundProbePlan.staggerMs(fg,1));assertEquals(1000L,AutoSearchCampaign.WAVE_PAUSE_MS)}
    @Test fun tenRealWorkersCanBeActiveAtOnce()=runBlocking {val active=java.util.concurrent.atomic.AtomicInteger();val peak=java.util.concurrent.atomic.AtomicInteger();ProbeRace.run((1..10).toList(),10,Int.MAX_VALUE,probe={val n=active.incrementAndGet();peak.updateAndGet {maxOf(it,n)};try {delay(30);Probe(20,null,null)}finally{active.decrementAndGet()}},onEach={_,_->});assertEquals(10,peak.get());assertEquals(0,active.get())}
    @Test fun freshLiveFailureErasesOldGreenProof() {val old=state();val x=LiveRouteEvidence.commit(old,probe.copy(realMs=0),profile,"wifi","",100,101);assertEquals("",x.autoChecksKey);assertEquals(0L,x.autoChecksAt)}
    @Test fun newLiveMeasurementCannotOverwriteNewerSampleOrDifferentMask() {val old=state().copy(checkedAt=200);assertEquals(old,LiveRouteEvidence.commit(old,probe,profile,"wifi","",100,201));assertEquals(state(),LiveRouteEvidence.commit(state(),probe,profile,"wifi","other",100,201))}
    @Test fun slowStableSeriesCanOfferWithoutBypassingJitter() {
        val slow=probe.copy(stableOk=false,callsOk=false)
        val measured=LatencyOnlyProbe.proof(slow,profile,true,true)
        assertTrue(measured.latencyOnlyOk);assertTrue(profile.accepts(measured.latencyConsented(),Int.MAX_VALUE))
        assertEquals(AutoChecks.key(profile,Int.MAX_VALUE),AutoChecks.stamp(measured,profile,1000))
        assertFalse(LatencyOnlyProbe.proof(slow,profile,false,true).latencyOnlyOk)
        assertFalse(LatencyOnlyProbe.proof(slow,profile,true,false).latencyOnlyOk)
        assertFalse(LatencyOnlyProbe.proof(slow.copy(discordOk=null),profile,true,true).latencyOnlyOk)
    }
    @Test fun ignoringLatencyStillRejectsMissingTelegramRepliesAndLargeSpread() {
        assertEquals(false,LinkStabilityPolicy.summarize(listOf(LinkStabilityPolicy.Sample(true,1800),LinkStabilityPolicy.Sample(false,0),LinkStabilityPolicy.Sample(true,1800)),Int.MAX_VALUE).stable)
        assertEquals(false,LinkStabilityPolicy.summarize(listOf(LinkStabilityPolicy.Sample(true,1000),LinkStabilityPolicy.Sample(true,2000),LinkStabilityPolicy.Sample(true,3000)),Int.MAX_VALUE).stable)
    }
    @Test fun reserveScoutCannotSwitchBasedOnTrafficCountersOrShortPing() {
        assertEquals(ReserveScoutPolicy.Action.VERIFY,ReserveScoutPolicy.next(2,0))
        assertEquals(ReserveScoutPolicy.Action.KEEP,ReserveScoutPolicy.next(0,3))
        assertEquals(ReserveScoutPolicy.Action.PREPARE,ReserveScoutPolicy.next(0,0))
        assertEquals(setOf("PREPARE","VERIFY","KEEP"),ReserveScoutPolicy.Action.values().map {it.name}.toSet())
    }
}
