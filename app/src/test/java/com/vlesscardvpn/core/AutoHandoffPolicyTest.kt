package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.RouteEvidence
import org.junit.Assert.*
import org.junit.Test

class AutoHandoffPolicyTest {
    private fun server(k:String)=Server("🇩🇪 $k","vless","$k.test",443,"fixture",security="tls",sni="$k.test")
    @Test fun previousEndpointIsAFirstCandidateNotAProof() {
        val old=server("old");val other=server("new")
        assertEquals(listOf(old,other),AutoHandoffPolicy.candidates(listOf(old),listOf(old,other),listOf(other,old)))
    }
    @Test fun removedEndpointCannotBeReintroduced() {
        val old=server("old");val other=server("new")
        assertEquals(listOf(other),AutoHandoffPolicy.candidates(listOf(old),listOf(other),listOf(other)))
    }
    @Test fun alteredTlsSettingCannotBeReintroducedByOldIdentity() {
        val old=server("old");val changed=old.copy(insecure=true)
        assertEquals(old.id,changed.id)
        assertTrue(AutoHandoffPolicy.candidates(listOf(old),listOf(changed),emptyList()).isEmpty())
    }
    @Test fun maximumEightCandidatesAndNoExtraNetworkScan() {
        val list=(1..40).map {server("s$it")}
        assertEquals(8,AutoHandoffPolicy.candidates(list,list,list).size)
        assertEquals(6500L,AutoHandoffPolicy.TOTAL_MS)
    }
    @Test fun oldPoolCannotStarveNewNetworkChallengers() {
        val old=(1..8).map {server("old$it")};val fresh=server("fresh")
        val wave=AutoHandoffPolicy.candidates(old,old+fresh,old+fresh)
        assertEquals(old.take(2),wave.take(2));assertEquals(fresh,wave[2]);assertEquals(8,wave.size)
    }
    @Test fun duplicatesDoNotConsumeBoundedWave() {
        val a=server("a");val b=server("b")
        assertEquals(listOf(a,b),AutoHandoffPolicy.candidates(listOf(a,a),listOf(a,b),listOf(a,b,b)))
    }
    @Test fun knownRussianExitRemainsExcluded() {
        val ru=server("ru").copy(name="🇷🇺 RU")
        assertTrue(AutoHandoffPolicy.candidates(listOf(ru),listOf(ru),listOf(ru)).isEmpty())
    }
    @Test fun oldDataAndServiceSuccessCannotQualifyOnNewNetwork() {
        val old=ServerState(realMs=20,bigOk=true,ytOk=true,tgOk=true,kbps=9000,serviceNetwork="Wi-Fi · old",serviceMask="",serviceCheckedAt=1000,speedCheckedAt=1000)
        val next=AutoHandoffPolicy.resetRuntime(old,"Моб.: оператор · CELL_unit")
        assertFalse(next.works);assertNull(next.bigOk);assertNull(next.ytOk);assertNull(next.tgOk)
        assertEquals(0,next.kbps);assertEquals(0L,next.serviceCheckedAt);assertEquals(0L,next.speedCheckedAt)
        assertEquals(1,AutoEligibility.serviceRank(next,"Моб.: оператор · CELL_unit",1001))
    }
    @Test fun historyIsPreservedOnlyForRankingAndReturnTrip() {
        val history=mapOf("Wi-Fi · old|mask" to RouteEvidence(checks=3,successes=2,at=1000))
        val old=ServerState(selected=true,realMs=30,okCount=8,failCount=2,goodMasks=listOf("mask"),netMasks=mapOf("Wi-Fi · old" to "mask"),netEvidence=history)
        val next=AutoHandoffPolicy.resetRuntime(old,"Wi-Fi · new")
        assertEquals(history,next.netEvidence);assertEquals(old.okCount,next.okCount);assertEquals(old.failCount,next.failCount)
        assertEquals("mask",next.netMasks["Wi-Fi · old"]);assertEquals("",next.maskFor("Wi-Fi · new"));assertTrue(next.selected);assertFalse(next.works)
    }
}
