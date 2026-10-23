package com.vlesscardvpn.tor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bridge presets in the generated torrc (1.2.42). */
class TorCardTorcR154Test {
    @Test
    fun `plain torrc has no bridge directives`() {
        val rc = TorCard.torrc()
        assertFalse(rc.contains("UseBridges"))
        assertFalse(rc.contains("ClientTransportPlugin"))
    }

    @Test
    fun `obfs4 and snowflake enable the matching transport`() {
        val obfs = TorCard.torrc(bridge = "obfs4")
        assertTrue(obfs.contains("ClientTransportPlugin obfs4 exec"))
        assertTrue(obfs.contains("UseBridges 1"))
        assertFalse(obfs.contains("snowflake"))
        val snow = TorCard.torrc(bridge = "snowflake")
        assertTrue(snow.contains("ClientTransportPlugin snowflake exec"))
        assertTrue(snow.contains("UseBridges 1"))
        assertFalse(snow.contains("obfs4"))
    }

    @Test
    fun `snowflake ships the built-in bridge line`() {
        val snow = TorCard.torrc(bridge = "snowflake")
        assertTrue(snow.contains("Bridge snowflake 192.0.2.3:1"))
        assertTrue(snow.contains("ice=stun:stun.l.google.com:19302"))
    }

    @Test
    fun `torrc keeps socks and control ports and cookie auth`() {
        val rc = TorCard.torrc(controlPort = 9055, socksPort = 9060)
        assertTrue(rc.contains("SocksPort 127.0.0.1:9060"))
        assertTrue(rc.contains("ControlPort 127.0.0.1:9055"))
        assertTrue(rc.contains("CookieAuthentication 1"))
    }
}
