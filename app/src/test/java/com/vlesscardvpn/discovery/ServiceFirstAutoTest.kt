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
    @Test fun zeroProofNeverEntersConnectedAutoPool() {
        val unknown=sample(5000).copy(serviceCheckedAt=0)
        assertTrue(AutoEligibility.servicePool(listOf(unknown),{it},"wifi",now).isEmpty())
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

    @Test fun noFallbackToFailedPartialOrStaleServicesEvenWhenAllCandidatesFail() {
        val invalid=listOf(sample(5000).copy(serviceTgOk=false),sample(5000).copy(serviceYtOk=false),
            sample(5000).copy(serviceTgOk=null),sample(5000).copy(serviceYtOk=null),
            sample(5000).copy(serviceCheckedAt=0),sample(5000).copy(serviceNetwork="cell"),
            sample(5000).copy(serviceCheckedAt=now-AutoEligibility.SERVICE_TTL_MS-1),
            sample(5000).copy(serviceCheckedAt=now+1),sample(5000).copy(serviceMask="other"))
        for (state in invalid) {
            assertTrue(AutoEligibility.servicePool(listOf(state),{it},"wifi",now).isEmpty())
            assertTrue(AutoEligibility.connectedPool(listOf(server(1)),{state},"wifi",now,false).isEmpty())
        }
    }
    @Test fun genericHttpsAndPageReachabilityAreNotServiceSuccess() {
        for (probe in listOf(Probe(100,true,null),Probe(100,true,true,tgOk=null),
            Probe(100,true,false,tgOk=true),Probe(100,true,true,tgOk=false),Probe(0,true,true,tgOk=true)))
            assertFalse(AutoEligibility.servicesWork(probe))
        assertTrue(AutoEligibility.servicesWork(Probe(100,null,true,tgOk=true)))
    }
    @Test fun unknownRouteCannotWinEvenWhenItHasTheOnlyFastPing() = kotlinx.coroutines.runBlocking {
        val route=server(1);val state=sample(100000,1).copy(serviceCheckedAt=0)
        val winner=AutoSelection.compare(listOf(route),{state},"wifi",{now},{0L},{true},{true},false) {_,_->fail("Unproven finalist")}
        assertNull(winner)
    }
    @Test fun serviceFailureDuringSpeedProbeInvalidatesFinalWinner() = kotlinx.coroutines.runBlocking {
        val route=server(1);var state=sample(0)
        val winner=AutoSelection.compare(listOf(route),{state},"wifi",{now},{0L},{true},{true},false) {_,_->state=sample(90000).copy(serviceTgOk=false)}
        assertNull(winner)
    }
    @Test fun inconclusiveRecheckCannotReuseEarlierSuccessfulServiceProof() {
        val old = sample(90000)
        val rechecked = AutoEligibility.recordServices(old, "wifi", "", now + 1, Probe(100, null, null))
        assertEquals(1, AutoEligibility.serviceRank(rechecked, "wifi", now + 1))
        assertTrue(AutoEligibility.servicePool(listOf(rechecked), { it }, "wifi", now + 1).isEmpty())
        assertNull(rechecked.serviceYtOk)
        assertNull(rechecked.serviceTgOk)
        assertEquals(old.speedCheckedAt, rechecked.speedCheckedAt)
        val partial = AutoEligibility.recordServices(old, "wifi", "", now + 1, Probe(100, null, true))
        assertEquals(1, AutoEligibility.serviceRank(partial, "wifi", now + 1))
    }

}
