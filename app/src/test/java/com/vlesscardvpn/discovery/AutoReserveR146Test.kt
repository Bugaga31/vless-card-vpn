package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test

class AutoReserveR146Test {
    private val now=1_000_000L
    private fun server(i: Int)=Server("reserve-$i","vless","host-$i.invalid",443,"fixture-$i")
    private fun state()=ServerState(realMs=40,httpsHosts=2,httpsNetwork="wifi",httpsCheckedAt=now)
    @Test fun periodicEvolutionCannotReplaceHealthyAutoMaskDuringPoolFill() {
        assertFalse(AutoReservePolicy.periodicMaskChangeAllowed(true,true))
        assertTrue(AutoReservePolicy.periodicMaskChangeAllowed(true,false))
        assertTrue(AutoReservePolicy.periodicMaskChangeAllowed(false,true))
    }
    @Test fun freshServicePassIsNotRepeatedForMissingReserveSlots() {
        val s=state().copy(serviceNetwork="wifi",serviceCheckedAt=now,serviceYtOk=true,serviceTgOk=true)
        assertTrue(AutoReservePolicy.pending(listOf(server(0)),{s},"wifi",now).isEmpty())
    }
    @Test fun boundedBatchCanReachRoutesBeyondInitialTwoOrEight() {
        val all=(0..19).map(::server)
        val proven=state().copy(serviceNetwork="wifi",serviceCheckedAt=now,serviceYtOk=true,serviceTgOk=true)
        val batch=AutoReservePolicy.pending(all,{if(it in all.take(12))proven else state()},"wifi",now)
        assertEquals(4,batch.size);assertTrue(batch.all {it in all.drop(12)})
    }
    @Test fun unknownRetryIsPacedWithoutInventingFailure() {
        val unknown=state().copy(serviceNetwork="wifi",serviceCheckedAt=now-1000)
        assertTrue(AutoReservePolicy.pending(listOf(server(0)),{unknown},"wifi",now).isEmpty())
        assertEquals(1,AutoReservePolicy.pending(listOf(server(0)),{unknown},"wifi",now+AutoReservePolicy.RETRY_MS).size)
        assertNull(unknown.serviceYtOk);assertNull(unknown.serviceTgOk)
    }
    @Test fun otherNetworkOrMaskHasNoTransferredPassOrRetryDelay() {
        val s=state().copy(serviceNetwork="cell",serviceCheckedAt=now,serviceYtOk=true,serviceTgOk=true)
        assertEquals(1,AutoReservePolicy.pending(listOf(server(0)),{s},"wifi",now).size)
        assertEquals(1,AutoReservePolicy.pending(listOf(server(0)),{s.copy(serviceNetwork="wifi",serviceMask="chrome.h4")},"wifi",now).size)
    }
    @Test fun deadAndKnownRussianRoutesCannotFillThePool() {
        assertTrue(AutoReservePolicy.pending(listOf(server(0)),{state().copy(realMs=0)},"wifi",now).isEmpty())
        assertTrue(AutoReservePolicy.pending(listOf(server(0).copy(name="RU 🇷🇺")),{state()},"wifi",now).isEmpty())
    }
    @Test fun fewerThanFourDoesNotFabricateCandidates() {
        assertEquals(2,AutoReservePolicy.pending((0..1).map(::server),{state()},"wifi",now).size)
        assertTrue(AutoReservePolicy.pending(emptyList(),{state()},"wifi",now).isEmpty())
    }
}
