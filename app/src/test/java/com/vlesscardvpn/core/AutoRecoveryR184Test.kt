package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test

/** Synthetic policy regressions only. Not executed in the experimental publication job. */
class AutoRecoveryR184Test {
    private fun node(i:Int)=Server("r184-$i","vless","host$i.example",443,"key$i",security="tls")
    @Test fun foregroundCanReprobeThreeCoolingHosts() {
        val all=(1..8).map(::node)
        val result=ConnectPolicy.candidates(all,all,emptySet(),cooling={true},recoveryProbeLimit=3){0}
        assertEquals(3,result.size);assertEquals(3,result.map {it.address}.distinct().size)
    }
    @Test fun normalBackgroundStillAllowsOnlyOneCoolingHost() {
        val all=(1..8).map(::node)
        assertEquals(1,ConnectPolicy.candidates(all,all,emptySet(),cooling={true}){0}.size)
    }
    @Test fun recoveryCannotRequestUnboundedParallelReprobes() {
        val all=(1..20).map(::node)
        assertEquals(3,ConnectPolicy.candidates(all,all,emptySet(),cooling={true},recoveryProbeLimit=999){0}.size)
    }
    @Test fun readyHostStillPreventsCoolingCloudReinsertion() {
        val a=node(1);val b=node(2)
        assertEquals(listOf(a),ConnectPolicy.candidates(listOf(a,b),listOf(b),emptySet(),cooling={it==b},recoveryProbeLimit=3){0})
    }
    @Test fun networkResetRequiresFreshNativeTelegramProof() {
        val old=ServerState(realMs=50,tgOk=true,tgMs=120,serviceNetwork="old",serviceMask="",serviceCheckedAt=900)
        val reset=AutoHandoffPolicy.resetRuntime(old,"new")
        assertEquals(1,AutoEligibility.telegramRank(reset,"new",1000))
        assertTrue(AutoEligibility.servicePool(listOf(reset),{it},"new",1000).isEmpty())
    }
    @Test fun freshHttpsAloneDoesNotAuthorizeHandoff() {
        val state=ServerState(realMs=40,tgOk=null,serviceNetwork="new",serviceMask="",serviceCheckedAt=1000)
        assertTrue(AutoEligibility.servicePool(listOf(state),{it},"new",1001).isEmpty())
    }
    @Test fun currentNetworkTelegramProofAuthorizesNoVideoStartup() {
        val state=ServerState(autoChecksKey=AutoChecks.key(AutoChecks.current,AutoLatencyPolicy.MAX_MS),autoChecksAt=1000,realMs=40,tgOk=true,tgMs=120,ytOk=null,serviceNetwork="new",serviceMask="",serviceCheckedAt=1000)
        assertEquals(listOf(state),AutoEligibility.servicePool(listOf(state),{it},"new",1001))
    }
    @Test fun staleMaskRescueCannotOverwriteNewerObservation() {
        val old=ServerState(realMs=40,checkedAt=1000)
        assertFalse(MaskSamplePolicy.unchanged(old.copy(checkedAt=1001),old,"new"))
    }
}
