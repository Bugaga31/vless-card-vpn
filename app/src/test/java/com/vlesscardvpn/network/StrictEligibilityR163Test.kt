package com.vlesscardvpn.core
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class StrictEligibilityR163Test {
    @Test fun allExplicitServiceFailuresCannotBecomeFallbackOrConnectedPool() {
        val route=Server("fixture","vless","192.0.2.1",443,"fixture")
        val state=ServerState(realMs=1,tgOk=false,serviceNetwork="wifi",serviceMask="",serviceCheckedAt=100)
        assertTrue(AutoEligibility.servicePool(listOf(route),{state},"wifi",101).isEmpty())
        assertTrue(AutoEligibility.connectedPool(listOf(route),{state},"wifi",101,false).isEmpty())
    }
    private val nonce=ByteArray(16) { it.toByte() }
    private fun response()=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN).putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(nonce).put(ByteArray(16){1}).put(8).put(ByteArray(8).apply { this[7]=15 }).put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(123).array()
    @Test fun zeroServerNonceIsNotTelegramProof() { assertTrue(TelegramProbe.validResponse(response(),nonce));val b=response();b.fill(0,40,56);assertFalse(TelegramProbe.validResponse(b,nonce)) }
    @Test fun invalidPqAndPaddingCannotBecomeTelegramPass() { for(offset in listOf(64,65)) { val b=response();b[offset]=1;assertFalse(TelegramProbe.validResponse(b,nonce)) };val b=response();b.fill(0,57,65);assertFalse(TelegramProbe.validResponse(b,nonce)) }
    @Test fun zeroRsaFingerprintAndEvenServerMessageAreRejected() { val b=response();b.fill(0,76,84);assertFalse(TelegramProbe.validResponse(b,nonce));val c=response();c[8]=2;assertFalse(TelegramProbe.validResponse(c,nonce)) }
    @Test fun servicePassCannotRescueIncompleteDataTransfer() { assertFalse(Probe(1,false,true,tgOk=true).works) }
    @Test fun measuredDownloadLeaderWinsDespiteLowerPingCompetitor() {
        val fast=Server("fast","vless","192.0.2.1",443,"fast")
        val lowPing=Server("low-ping","vless","198.51.100.1",443,"low-ping")
        fun state(s:Server)=ServerState(realMs=if(s==fast) 500 else 5,kbps=if(s==fast) 30000 else 1000,speedNetwork="wifi",speedMask="",speedCheckedAt=100)
        assertEquals(fast,AutoEligibility.connectedPool(listOf(lowPing,fast),::state,"wifi",101,false).first())
    }
}
