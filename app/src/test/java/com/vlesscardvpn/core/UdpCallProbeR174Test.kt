package com.vlesscardvpn.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UdpCallProbeR174Test {
    @Test fun bindingRequestUsesTheStunCookieAndTransactionId() {
        val id = ByteArray(12) { it.toByte() }
        val request = UdpCallProbe.bindingRequest(id)
        assertArrayEquals(byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xa4.toByte(), 0x42) + id, request)
    }

    @Test fun onlyAMatchingBindingResponsePasses() {
        val id = ByteArray(12) { (it + 10).toByte() }
        val response = UdpCallProbe.bindingRequest(id).copyOf().also { it[0] = 1 }
        assertTrue(UdpCallProbe.isBindingResponse(response, response.size, id))
        assertFalse(UdpCallProbe.isBindingResponse(response, response.size, ByteArray(12)))
        response[0] = 0
        assertFalse(UdpCallProbe.isBindingResponse(response, response.size, id))
    }

    @Test fun socksUdpFramesPreserveTheDomainAndPayload() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val frame = UdpCallProbe.socksUdpFrame("stun.example", 19302, payload)
        assertEquals(3, frame[3].toInt())
        val response = byteArrayOf(0, 0, 0, 3, 5) + "relay".toByteArray() + byteArrayOf(0x4b, 0x86.toByte()) + payload
        assertArrayEquals(payload, UdpCallProbe.socksUdpPayload(response, response.size))
        assertFalse(UdpCallProbe.socksUdpPayload(byteArrayOf(1, 0, 0, 3, 4), 5) != null)
    }

    @Test fun invalidBufferLengthsNeverPassOrThrow() {
        val id = ByteArray(12)
        assertFalse(UdpCallProbe.isBindingResponse(ByteArray(1), 20, id))
        assertFalse(UdpCallProbe.isBindingResponse(ByteArray(20), -1, id))
        assertTrue(UdpCallProbe.socksUdpPayload(ByteArray(4), 100) == null)
        assertTrue(UdpCallProbe.socksUdpPayload(ByteArray(4), -1) == null)
    }

    @Test fun declaredStunLengthMustMatchDatagram() {
        val id = ByteArray(12)
        val response = UdpCallProbe.bindingRequest(id).also { it[0] = 1 }
        assertFalse(UdpCallProbe.isBindingResponse(response + byteArrayOf(0), 21, id))
    }

    private fun assertEquals(expected: Int, actual: Int) = org.junit.Assert.assertEquals(expected, actual)
}
