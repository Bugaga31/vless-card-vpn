package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test

class ServiceFirstAutoTest {
    private val now=1_000_000L
    private fun sample(speed:Int,ms:Int=100)=ServerState(realMs=ms,kbps=speed,
        speedNetwork="wifi",speedMask="",speedCheckedAt=now,serviceNetwork="wifi",
        serviceMask="",serviceCheckedAt=now,serviceYtOk=true,serviceTgOk=true)
    private fun server(n:Int)=Server("DE-$n","vless","s$n.invalid",443,"secret-$n")
    @Test fun unknownLowPingAndHighThroughputNeverOutrankBothServicesVerified() {
        val good=sample(5000,400)
        val unknown=sample(90000,1).copy(serviceCheckedAt=0)
        assertTrue(ServerChoice.compareDownload(good,unknown,"wifi",now)<0)
        assertFalse(ServerChoice.downloadUpgrade(good,unknown,"wifi",now))
        assertTrue(ServerChoice.downloadUpgrade(unknown,good,"wifi",now))
    }
    @Test fun eitherFailedServiceLosesEvenAtMuchHigherSpeed() {
        val good=sample(5000)
        for(bad in listOf(sample(90000).copy(serviceTgOk=false),sample(90000).copy(serviceYtOk=false))) {
            assertTrue(ServerChoice.compareDownload(good,bad,"wifi",now)<0)
            assertFalse(ServerChoice.downloadUpgrade(good,bad,"wifi",now))
        }
    }
    @Test fun missingEitherServiceIsNotProofOfBoth() {
        val good=sample(5000)
        for(unknown in listOf(sample(90000).copy(serviceTgOk=null),sample(90000).copy(serviceYtOk=null)))
            assertEquals(listOf(good),AutoEligibility.servicePool(listOf(unknown,good),{it},"wifi",now))
    }
    @Test fun fastestOfVerifiedWinsNotLowestPing() {
        assertTrue(ServerChoice.compareDownload(sample(30000,700),sample(5000,5),"wifi",now)<0)
    }
    @Test fun connectedPoolFiltersUnknownAndFailedEvenWhenCallerDoesNot() {
        val routes=(0..9).map(::server)
        val states=routes.mapIndexed { i,s -> s.id to when(i) {
            8->sample(99000).copy(serviceTgOk=false)
            9->sample(100000).copy(serviceCheckedAt=0)
            else->sample((i+1)*1000)
        }}.toMap()
        for(gaming in listOf(false,true)) {
            val result=AutoEligibility.connectedPool(routes,{states.getValue(it.id)},"wifi",now,gaming)
            assertEquals(8,result.size)
            assertEquals(routes.take(8).toSet(),result.toSet())
        }
    }
    @Test fun staleForeignOrChangedMaskCannotCertifyServiceEligibility() {
        val good=sample(5000)
        for(unknown in listOf(sample(90000).copy(serviceNetwork="cell"),sample(90000).copy(serviceMask="other"),
            sample(90000).copy(serviceCheckedAt=now-AutoEligibility.SERVICE_TTL_MS-1))) {
            assertEquals(listOf(good),AutoEligibility.servicePool(listOf(unknown,good),{it},"wifi",now))
        }
    }
    @Test fun zeroProofDoesNotPreventProvisionalRecovery() {
        val unknown=sample(5000).copy(serviceCheckedAt=0)
        assertEquals(listOf(unknown),AutoEligibility.servicePool(listOf(unknown),{it},"wifi",now))
        val failed=sample(5000).copy(serviceTgOk=false)
        assertFalse(ServerChoice.downloadUpgrade(null,failed,"wifi",now))
    }
    @Test fun finalistsDoNotMixGenericHttpsWithConfirmedServiceRoutes() {
        val a=server(21);val b=server(22)
        val states=mapOf(a.id to sample(5000),b.id to sample(99000).copy(serviceCheckedAt=0))
        assertEquals(listOf(a),AutoSelection.finalists(listOf(b,a),{states.getValue(it.id)},"wifi",now))
    }
    @Test fun aFasterMaskMustPreserveBothServiceChecksBeforeReplacingTheCurrentMask() {
        val old=sample(5000)
        for(probe in listOf(Probe(10,true,null,kbps=90000),Probe(10,true,false,tgOk=true,kbps=90000),
            Probe(10,true,true,tgOk=false,kbps=90000)))
            assertFalse(AutoRoutePolicy.serviceSafeImprovement(old,"wifi",now,probe))
        val good=Probe(10,true,true,tgOk=true,kbps=90000)
        assertTrue(AutoRoutePolicy.serviceSafeImprovement(old,"wifi",now,good))
        val changed=AutoRoutePolicy.apply(old,"wifi","chrome.h6",now,good)
        assertEquals(0,AutoEligibility.serviceRank(changed,"wifi",now))
        assertEquals("chrome.h6",changed.serviceMask)
    }

}
