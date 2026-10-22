package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test

class WebBackgroundSeparationTest {
    @Test fun quickWebUnknownDoesNotEraseEightProvenBackgroundPoolMembers() {
        val states=(1..8).map { ServerState(realMs=25,bigOk=true,serviceNetwork="wifi",serviceCheckedAt=1000,
            serviceTgOk=true,serviceYtOk=true,httpsNetwork="wifi",httpsCheckedAt=1000,httpsHosts=2) }
        val after=states.map { AutoEligibility.recordServices(it,"wifi","",2000,
            Probe(25,true,null,web=ServiceWebProbe.Result())) }
        assertEquals(states,after)
        assertEquals(8,AutoEligibility.connectionPool(after,{it},"wifi",2001).size)
    }
    @Test fun webFailCannotInvalidateOrRestampIndependentProtocolReceipts() {
        val state=ServerState(realMs=25,serviceNetwork="wifi",serviceCheckedAt=1000,serviceTgOk=true,serviceYtOk=true)
        val web=Probe(25,true,null,web=ServiceWebProbe.Result(false,false))
        assertEquals(state,AutoEligibility.recordChainServices(state,"wifi","",1500,2000,web,true))
        assertEquals(state,AutoEligibility.recordChainServices(state,"wifi","",1500,2000,web,false))
    }
    @Test fun deepExplicitMtprotoFailureStillReplacesPreviousProtocolPass() {
        val state=ServerState(realMs=25,serviceNetwork="wifi",serviceCheckedAt=1000,serviceTgOk=true,serviceYtOk=true)
        val after=AutoEligibility.recordServices(state,"wifi","",2000,Probe(25,true,true,tgOk=false))
        assertEquals(false,after.serviceTgOk)
        assertTrue(AutoEligibility.connectionPool(listOf(after),{it},"wifi",2001).isEmpty())
    }
    private val data = Probe(25,true,null,httpsHosts=2,httpsChecked=true)
    @Test fun genericTransferDoesNotImplicitlyDisableDeepProtocolChecks() {
        assertEquals(ServiceProbePolicy.Checks.PROTOCOL,ServiceProbePolicy.checks(true))
    }
    @Test fun liveFastCheckExplicitlySelectsWebWithoutProtocolWork() {
        assertEquals(ServiceProbePolicy.Checks.WEB,ServiceProbePolicy.checks(true,true))
    }
    @Test fun dataOnlyAdmissionStartsNoServiceCheck() {
        assertEquals(ServiceProbePolicy.Checks.NONE,ServiceProbePolicy.checks(false,false))
        assertEquals(ServiceProbePolicy.Checks.NONE,ServiceProbePolicy.checks(false,true))
    }
    @Test fun immediateReceiptKeepsRealTwoOriginTransferButWebIsNotProtocolPass() {
        val result=RecentServiceProof(data,"wifi","h",1000).withWeb(ServiceWebProbe.Result(true,true),"wifi","h",2000)!!
        assertTrue(AutoEligibility.connectionWorks(result));assertFalse(AutoEligibility.servicesWork(result))
        assertNull(result.tgOk);assertNull(result.ytOk);assertEquals(true,result.web?.telegram);assertEquals(2,result.httpsHosts)
    }
    @Test fun webCannotInventDataTransfer() {
        val receipt=RecentServiceProof(Probe(0,null,null),"wifi","h",1000)
        assertNull(receipt.withWeb(ServiceWebProbe.Result(true,true),"wifi","h",2000))
    }
    @Test fun oneControlOriginCannotBePromotedByTwoWorkingWebPages() {
        assertNull(RecentServiceProof(data.copy(httpsHosts=1),"wifi","h",1000)
            .withWeb(ServiceWebProbe.Result(true,true),"wifi","h",2000))
    }
    @Test fun protocolOnlyReceiptCannotBePromotedIntoTwoOriginData() {
        assertNull(RecentServiceProof(Probe(25,null,true,tgOk=true),"wifi","h",1000)
            .withWeb(ServiceWebProbe.Result(true,true),"wifi","h",2000))
    }
    @Test fun changedNetworkRejectsPreviousAdmissionEvenIfWebPagesAnswer() {
        assertNull(RecentServiceProof(data,"wifi","h",1000).withWeb(ServiceWebProbe.Result(true,true),"mobile","h",2000))
    }
    @Test fun changedPhysicalHandleRejectsPreviousAdmission() {
        assertNull(RecentServiceProof(data,"wifi","h",1000).withWeb(ServiceWebProbe.Result(true,true),"wifi","other",2000))
    }
    @Test fun staleAndFutureDatedReceiptsAreNotReused() {
        val receipt=RecentServiceProof(data,"wifi","h",1000)
        assertNull(receipt.withWeb(ServiceWebProbe.Result(true,true),"wifi","h",31001))
        assertNull(receipt.withWeb(ServiceWebProbe.Result(true,true),"wifi","h",999))
    }
    @Test fun webTimeoutKeepsHonestUnknownAndNeverInventsServicePass() {
        val result=RecentServiceProof(data,"wifi","h",1000).withWeb(ServiceWebProbe.Result(),"wifi","h",2000)!!
        assertNull(result.web?.telegram);assertNull(result.web?.youtube);assertFalse(AutoEligibility.servicesWork(result))
    }
    @Test fun explicitProtocolFailureIsNeverReusedForAdmission() {
        assertNull(RecentServiceProof(data.copy(tgOk=false),"wifi","h",1000)
            .withWeb(ServiceWebProbe.Result(true,true),"wifi","h",2000))
    }
}
