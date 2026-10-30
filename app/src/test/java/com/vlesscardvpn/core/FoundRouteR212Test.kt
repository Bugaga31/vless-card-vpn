package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test

class FoundRouteR212Test {
    data class Candidate(val id:String,val ms:Int,val valid:Boolean=true)
    private fun rank(vararg c:Candidate)=FoundRoutePolicy.rank(c.toList(),{it.valid},{it.ms},{it.id})
    @Test fun countsDistinctFoundRoutesAndChoosesFastest() {
        val r=rank(Candidate("a",1800),Candidate("b",1200),Candidate("a",2000))
        assertEquals(2,r.size);assertEquals("b",r.first().id)
    }
    @Test fun failedStaleAndWrongContextNotCounted() {
        assertTrue(rank(Candidate("failed",1,false),Candidate("stale",2,false),Candidate("network",3,false)).isEmpty())
    }
    @Test fun countUpdatesAfterFreshFailure() {
        assertEquals(2,rank(Candidate("a",1200),Candidate("b",1300)).size)
        assertEquals(1,rank(Candidate("a",1200,false),Candidate("b",1300)).size)
    }
    @Test fun capDoesNotChangeBestCandidate() {
        val r=FoundRoutePolicy.rank((1..1000).map {Candidate("s$it",it)},{it.valid},{it.ms},{it.id})
        assertEquals(64,r.size);assertEquals("s1",r.first().id)
    }
    @Test fun emptyMeansNoButtonCandidate() {assertTrue(rank().isEmpty())}
    private val profile=AutoCheckProfile.of(AutoCheckProfile.allowed)
    private fun state()=ServerState(realMs=1800,tgOk=true,tgMs=1600,ytOk=true,checkedAt=100,
        autoChecksKey=AutoChecks.key(profile,Int.MAX_VALUE),autoChecksAt=100,serviceNetwork="wifi",serviceMask="")
    @Test fun tcpOnlyCannotBecomeEarlyOffer() {
        assertFalse(SlowRoutePolicy.measured(ServerState(tcpMs=20),profile,1000,"wifi",101))
    }
    @Test fun freshSelectedProofCanBeShownBeforeWaveEnds() {
        assertTrue(SlowRoutePolicy.measured(state(),profile,1000,"wifi",101))
    }
    @Test fun latestYoutubeFailureOrUnknownRejectsOldProof() {
        for(ok in listOf(false,null)) assertFalse(SlowRoutePolicy.measured(state().copy(ytOk=ok),profile,1000,"wifi",101))
    }
    @Test fun latestTelegramFailureOrUnknownRejectsOldProof() {
        for(ok in listOf(false,null)) assertFalse(SlowRoutePolicy.measured(state().copy(tgOk=ok),profile,1000,"wifi",101))
    }
    @Test fun changedNetworkAndMaskInvalidateEarlyProof() {
        assertFalse(SlowRoutePolicy.measured(state(),profile,1000,"cell",101))
        assertFalse(SlowRoutePolicy.measured(state().copy(maskId="changed"),profile,1000,"wifi",101))
    }
    @Test fun fresh578msManualProofIsAutoReady() {
        val server=com.vlesscardvpn.model.Server("fixture","vless","192.0.2.1",443,"fixture")
        val ready=state().copy(realMs=578,tgMs=578,autoChecksKey=AutoChecks.key(profile,1000))
        assertEquals("Проверки Авто пройдены",AutoReadiness.describe(server,ready,profile,1000,"wifi",101))
        assertTrue(profile.accepts(Probe(578,null,true,tgOk=true,tgMs=578,stableOk=true,callsOk=true,discordOk=true),1000))
    }
    @Test fun httpsHistoryDoesNotPretendToBeFreshSelectedServiceProof() {
        val server=com.vlesscardvpn.model.Server("fixture","vless","192.0.2.1",443,"fixture")
        val history=state().copy(realMs=578,tgMs=578,autoChecksKey="")
        assertTrue(history.works)
        assertEquals("Нужна свежая проверка выбранных сервисов",AutoReadiness.describe(server,history,profile,1000,"wifi",101))
    }
    @Test fun explains578msRejectionWithoutBlamingProvider() {
        val reason=AutoAdmissionReason.describe(Probe(578,null,true,tgOk=true,tgMs=580,discordOk=false,stableOk=true,callsOk=true),profile,1000)
        assertTrue(reason.contains("HTTPS 578 мс"));assertTrue(reason.contains("Discord — проверка не прошла"))
        assertFalse(reason.contains("провайдер"))
    }
}
