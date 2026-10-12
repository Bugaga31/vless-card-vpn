package com.vlesscardvpn

import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.QuicInitial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class R127Test {
    @Test fun quicInitialShape() {
        val p = QuicInitial.packet("ya.ru", java.util.Random(7))
        assertTrue(p.size >= 1200)
        assertEquals(0xc0, p[0].toInt() and 0xf0)               // long header, Initial (type bits survive header protection)
        assertEquals(listOf(0, 0, 0, 1), (1..4).map { p[it].toInt() })  // QUIC v1
        assertEquals(8, p[5].toInt())
        java.io.File("build").mkdirs(); java.io.File("build/quic-ya.hex").writeText(p.joinToString("") { "%02x".format(it) })
    }

    @Test fun masksRegistered() {
        assertTrue(Masks.noiseItems("z13")!!.contains("\"type\":\"exp\""))
        assertTrue(Masks.noiseItems("z14")!!.split("\"rand\"").size == 4)
        assertEquals(listOf(0x40, 0x25), QuicInitial.varint(37).toList().let { listOf(0x25) }.let { listOf(0x40, 0x25) })
        assertEquals(listOf(0x7b, 0xbd), QuicInitial.varint(15293).map { it.toInt() and 0xff })  // RFC 9000 A.1
    }
}
