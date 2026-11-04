package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.RouteEvidence
import org.junit.Assert.*
import org.junit.Test

class AdaptiveQueueR182Test {
    private fun server(i:Int)=Server("server$i","vless","host$i.example",443,"key$i",security="tls")
    @Test fun coolingRoutesAreNotInjectedByCloudOrCatalog() {
        val a=server(1);val b=server(2)
        val picked=ConnectPolicy.candidates(listOf(a,b),listOf(b),emptySet(),cooling={it==b}) {0}
        assertEquals(listOf(a),picked)
    }
    @Test fun allCoolingAllowsOnlyOneRecoveryCandidate() {
        val all=(1..12).map(::server)
        assertEquals(1,ConnectPolicy.candidates(all,all,emptySet(),cooling={true}){0}.size)
    }
    @Test fun freshServiceFailureDoesNotReenterFromCloud() {
        val a=server(1);val b=server(2)
        assertEquals(listOf(a),ConnectPolicy.candidates(listOf(a,b),listOf(b),emptySet(),serviceRank={if(it==b) 2 else 1}){0})
    }
    @Test fun unknownCloudValuesNeverBecomeLocalSuccess() {
        val s=ServerState()
        val score=AdaptiveRouteRank.priority(s,"wifi",1000,1.0)
        assertTrue(score.isFinite());assertEquals(0,s.evidenceFor("wifi").successes)
        assertEquals(1,AutoEligibility.telegramRank(s,"wifi",1000))
    }
    @Test fun largeCountersStayFiniteAndBounded() {
        val state=ServerState(netEvidence=mapOf("wifi|" to RouteEvidence(checks=1_000_000,successes=999_999,ema=.99,latency=50.0,at=1000)))
        val score=AdaptiveRouteRank.priority(state,"wifi",1001)
        assertTrue(score.isFinite() && score in -.25..1.0)
    }
    @Test fun failedBudgetDoesNotGrowWithoutBound() {
        val key="isolated-test-network"
        assertTrue(ReserveBudget.take(key,1_000));assertTrue(ReserveBudget.take(key,1_000))
        assertFalse(ReserveBudget.take(key,1_000));assertFalse(ReserveBudget.take(key,60_999))
        assertTrue(ReserveBudget.take(key,61_000))
    }
}
