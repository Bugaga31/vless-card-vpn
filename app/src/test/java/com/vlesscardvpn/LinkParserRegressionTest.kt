package com.vlesscardvpn.model

import org.junit.Assert.*
import org.junit.Test

class LinkParserRegressionTest {

    @Test
    fun vmessWithQueryParamsIsAccepted() {
        val payload = Base64.encode("""{"v":"2","ps":"","add":"vm.example.com","port":"443","id":"uuid-vm","net":"ws","tls":"tls"}""")
        val s = LinkParser.parse("vmess://$payload?remarks=%D0%A0%D0%A4&path=/ws")
        assertNotNull("vmess with query must parse", s)
        assertEquals("vm.example.com", s!!.address)
        assertEquals(443, s.port)
        assertEquals("РФ", s.name)
    }

    @Test
    fun vmessNumericPortAsDoubleIsAccepted() {
        val payload = Base64.encode("""{"ps":"d","add":"1.2.3.4","port":443.0,"id":"u","net":"tcp"}""")
        val s = LinkParser.parse("vmess://$payload")
        assertNotNull("float-style port must parse", s)
        assertEquals(443, s!!.port)
    }

    @Test
    fun vmessGarbageStillRejected() {
        assertNull(LinkParser.parse("vmess://not-base64-json"))
    }
}