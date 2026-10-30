package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoRokiR216Test {
    private fun route()=Server("fixture","vless","192.0.2.1",443,"fixture",security="tls",insecure=true)
    @Test fun firstTapUsesExactlyTheNewlyPinnedServer()=runBlocking {
        val old=route();val pinned=old.copy(pcs="ab".repeat(32));val ledger=FoundCandidateLedger(listOf(old))
        var servers=listOf(old);var proved=false;var probes=0;var selected:Server?=null
        val result=FoundConnectSequence.run({true},{
            val candidates=ledger.current(servers)
            if(proved && candidates.isNotEmpty()) {selected=candidates.single();true} else false
        },{probes++;servers=listOf(pinned);ledger.verified(pinned);proved=true})
        assertEquals(FoundConnectSequence.Result.READY,result);assertEquals(1,probes);assertEquals(pinned,selected)
    }
    @Test fun unchangedReadyServerDoesNotNeedPinReplacement() {
        val s=route().copy(pcs="existing");val ledger=FoundCandidateLedger(listOf(s))
        assertEquals(listOf(s),ledger.current(listOf(s)))
    }
    @Test fun arbitraryCurrentPinWithoutThisProbeIsNotAccepted() {
        val s=route();assertTrue(FoundCandidateLedger(listOf(s)).current(listOf(s.copy(pcs="new"))).isEmpty())
    }
    @Test fun changedExistingPinCannotBeBlessed() {
        val s=route().copy(pcs="existing");val l=FoundCandidateLedger(listOf(s));l.verified(s.copy(pcs="new"))
        assertTrue(l.current(listOf(s.copy(pcs="new"))).isEmpty())
    }
    @Test fun changedCredentialsTransportAndSniRemainRejected() {
        val s=route();val variants=listOf(s.copy(secret="other"),s.copy(sni="other.test"),s.copy(network="ws"),s.copy(fp="other"))
        for(changed in variants) {val l=FoundCandidateLedger(listOf(s));l.verified(changed.copy(pcs="new"));assertTrue(l.current(listOf(changed.copy(pcs="new"))).isEmpty())}
    }
    @Test fun pinCasNeverOverwritesUserEdits() {
        val s=route();val changed=s.copy(fp="edited");assertEquals(changed,FoundCandidateLedger.applyPin(changed,s,s.copy(pcs="new")))
    }
    @Test fun pinCasAcceptsOnlyPinAndOnlyForEligibleOriginal() {
        val s=route();val p=s.copy(pcs="new");assertEquals(p,FoundCandidateLedger.applyPin(s,s,p))
        assertEquals(s,FoundCandidateLedger.applyPin(s,s,p.copy(secret="other")))
        val secure=s.copy(insecure=false);assertEquals(secure,FoundCandidateLedger.applyPin(secure,secure,secure.copy(pcs="new")))
    }
    @Test fun concurrentTcpPingDoesNotDiscardServiceResult() {
        val old=ServerState(realMs=578,tcpMs=20,checkedAt=100)
        assertTrue(ProbePublication.unchangedExceptPing(old.copy(tcpMs=30,checkedAt=300),old))
    }
    @Test fun newerServiceFailureCannotBeOverwritten() {
        val old=ServerState(realMs=578,checkedAt=100)
        assertFalse(ProbePublication.unchangedExceptPing(old.copy(realMs=0,checkedAt=300),old))
    }
    @Test fun newerProfileMaskOrSelectionIsAFence() {
        val old=ServerState()
        listOf(old.copy(autoChecksKey="different"),old.copy(maskId="different"),old.copy(selected=true),old.copy(ytOk=false)).forEach {
            assertFalse(ProbePublication.unchangedExceptPing(it,old))
        }
    }
    @Test fun unknownIsNotDisabledOrPassed() {
        assertEquals("не выбрано",ServiceProbeStatus.label(false,null,null))
        assertFalse(ServiceProbeStatus.label(true,null,null).contains("не выбрано"))
        assertFalse(ServiceProbeStatus.label(true,null,null).contains("✓"))
    }
    @Test fun timeoutAndCancellationHaveDistinctLabels() {
        assertNotEquals(ServiceProbeStatus.label(true,null,"TIMEOUT"),ServiceProbeStatus.label(true,null,"CANCELLED"))
        assertTrue(ServiceProbeStatus.label(true,null,"TIMEOUT").contains("время"))
    }
    @Test fun missingYoutubeMediaIsNotClaimedToBeBlocking() {
        val text=ServiceProbeStatus.label(true,null,"YT_NO_DIRECT_MEDIA")
        assertTrue(text.contains("ссылка"));assertFalse(text.contains("заблокирован"))
    }
    @Test fun mandatoryUnknownYoutubeCannotConnect() {
        assertFalse(AutoCheckProfile.of(listOf("youtube")).accepts(Probe(578,true,null),800))
    }
    @Test fun mandatoryUnknownTelegramCannotConnect() {
        assertFalse(AutoCheckProfile.of(listOf("telegram")).accepts(Probe(578,true,null,tgOk=null),800))
    }
    @Test fun rokiStartsUntrainedAndPreservesOrder() {
        val model=RokiRanker();val servers=listOf(route(),route().copy(address="192.0.2.2"))
        assertEquals(0,model.samples("a"));assertEquals(servers,model.order("a",servers){ServerState()})
    }
    @Test fun rokiIgnoresUnknownAndCancelledResults() {
        val model=RokiRanker();repeat(20) {model.observe("a",ServerState(),null);model.observe("a",ServerState(),true,true)}
        assertEquals(0,model.samples("a"))
    }
    @Test fun rokiNeedsEightCompletedSamplesBeforeRanking() {
        val model=RokiRanker();val s=listOf(route(),route().copy(address="192.0.2.2"));repeat(7){model.observe("a",ServerState(),true)}
        assertEquals(7,model.samples("a"));assertEquals(s,model.order("a",s){ServerState()})
    }
    @Test fun rokiLearnsOrderingButDoesNotRemoveAnyRoute() {
        val m=RokiRanker();val good=route();val bad=good.copy(address="192.0.2.2")
        val state:(Server)->ServerState={if(it==good) ServerState(realMs=50,bigOk=true,okCount=10) else ServerState(realMs=1500,failCount=10)}
        repeat(100){m.observe("a",state(good),true);m.observe("a",state(bad),false)}
        assertEquals(listOf(good,bad),m.order("a",listOf(bad,good),state));assertEquals(200,m.samples("a"))
    }
    @Test fun rokiContextSeparatesNetworkProfileLimitAndTestUrl() {
        val a=AutoCheckProfile.of(listOf("youtube"));val base=Roki.context("wifi",a,800,"https://example.test")
        val variants=listOf(Roki.context("mobile",a,800,"https://example.test"),Roki.context("wifi",AutoCheckProfile.of(listOf("telegram")),800,"https://example.test"),Roki.context("wifi",a,1000,"https://example.test"),Roki.context("wifi",a,800,"https://other.test"))
        variants.forEach {assertNotEquals(base,it)}
    }
    @Test fun rokiHasBoundedContextMemory() {
        val m=RokiRanker();repeat(30){m.observe("context$it",ServerState(),true)}
        assertEquals(0,m.samples("context0"));assertEquals(1,m.samples("context29"))
    }
    @Test fun failedSelectedGateRemainsFailedRegardlessOfTraining() {
        val p=AutoCheckProfile.of(listOf("youtube"));val failed=Probe(50,true,false)
        val m=RokiRanker();repeat(100){m.observe("a",ServerState(),true)}
        assertEquals(false,p.verdict(failed,800));assertFalse(p.accepts(failed,800))
    }
}
