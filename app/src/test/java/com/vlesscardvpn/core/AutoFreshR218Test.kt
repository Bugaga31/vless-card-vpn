package com.vlesscardvpn.core
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class AutoFreshR218Test {
    private fun server(n:Int)=Server("fixture$n","vless","192.0.2.$n",443,"fixture$n")
    @Test fun completeFreshCandidateIsNotLostBehind64OldFastResults() {
        val list=(1..70).map(::server);val ready=list.last()
        val state:(Server)->ServerState={ServerState(realMs=if(it==ready) 578 else 10,bigOk=true)}
        assertFalse(ready in FoundConnectPolicy.shortlist(list,state))
        val shortlist=FoundConnectPolicy.shortlist(list,state){it==ready}
        assertEquals(64,shortlist.size);assertEquals(ready,shortlist.first())
    }
    @Test fun noFreshProofPreservesExistingLatencyOrderAndCap() {
        val list=(1..80).map(::server);val state:(Server)->ServerState={ServerState(realMs=100+it.name.removePrefix("fixture").toInt(),bigOk=true)}
        assertEquals(FoundConnectPolicy.shortlist(list,state),FoundConnectPolicy.shortlist(list,state){false})
    }
    @Test fun priorityCannotAdmitFailedOrExcludedRoute() {
        val failed=server(1);val excluded=server(2).copy(name="🇷🇺 RU");val good=server(3)
        val shortlist=FoundConnectPolicy.shortlist(listOf(failed,excluded,good),{ServerState(realMs=if(it==failed) 0 else 578,bigOk=true)}){true}
        assertEquals(listOf(good),shortlist)
    }
    @Test fun priorityDoesNotDuplicateSameServer() {
        val s=server(1);assertEquals(listOf(s),FoundConnectPolicy.shortlist(listOf(s,s),{ServerState(realMs=578)}){true})
    }
    @Test fun localPreparationKeepsSlowPinnedRouteWithoutFakingServiceSuccess() {
        val s=server(1).copy(security="tls",insecure=true);val pinned=s.copy(pcs="ab".repeat(32))
        val ledger=FoundCandidateLedger(listOf(s));ledger.prepared(pinned)
        assertEquals(listOf(pinned),ledger.current(listOf(pinned)))
        assertFalse(AutoCheckProfile.of(listOf("youtube")).accepts(Probe(578,true,null),800))
    }
    @Test fun preparedPinStillRejectsChangedCredentialsAndExistingPinReplacement() {
        val s=server(1).copy(security="tls",insecure=true)
        val changed=s.copy(pcs="new",secret="edited");val ledger=FoundCandidateLedger(listOf(s));ledger.prepared(changed)
        assertTrue(ledger.current(listOf(changed)).isEmpty())
        val old=s.copy(pcs="old");val replacement=old.copy(pcs="new");val locked=FoundCandidateLedger(listOf(old));locked.prepared(replacement)
        assertTrue(locked.current(listOf(replacement)).isEmpty())
    }
    @Test fun exactFreshCandidateSkipsProbeWave()=runBlocking {
        val list=(1..70).map(::server);val ready=list.last();var probes=0
        val shortlist=FoundConnectPolicy.shortlist(list,{ServerState(realMs=578)}){it==ready}
        assertEquals(FoundConnectSequence.Result.READY,FoundConnectSequence.run({true},{ready in shortlist},{probes++}))
        assertEquals(0,probes)
    }
    @Test fun changedNetworkDoesNotSelectFreshCandidate()=runBlocking {
        var selects=0
        assertEquals(FoundConnectSequence.Result.STALE,FoundConnectSequence.run({false},{selects++;true},{}))
        assertEquals(0,selects)
    }
}
