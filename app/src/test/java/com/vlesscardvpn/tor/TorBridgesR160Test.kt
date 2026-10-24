package com.vlesscardvpn.tor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R160: pasted bridge lines land in the embedded torrc (no Termux needed). */
class TorBridgesR160Test {
    private val lines = listOf(
        "Bridge obfs4 1.2.3.4:443 CERT.abc xyz iat-mode=0",
        "мусорная строка без Bridge",
        "  Bridge obfs4 5.6.7.8:443 CERT.def uvw iat-mode=1  ",
    )

    @Test
    fun `bridge lines are written into torrc and junk dropped`() {
        val rc = TorCard.torrcEmbedded("/d", "/c", "/bin", bridge = "obfs4", bridgeLines = lines)
        assertTrue("UseBridges on", "UseBridges 1" in rc)
        assertTrue("first bridge kept", "Bridge obfs4 1.2.3.4:443" in rc)
        assertTrue("trimmed second kept", "Bridge obfs4 5.6.7.8:443" in rc)
        assertFalse("junk dropped", "мусорная" in rc)
        assertTrue("plugin line present", "ClientTransportPlugin obfs4,webtunnel exec /bin/libLyrebird.so" in rc)
    }

    @Test
    fun `no bridge lines means placeholder comment only`() {
        val rc = TorCard.torrcEmbedded("/d", "/c", "/bin", bridge = "obfs4", bridgeLines = emptyList())
        assertTrue("UseBridges on", "UseBridges 1" in rc)
        assertFalse("no real Bridge lines", rc.lines().any { it.startsWith("Bridge ") })
    }

    @Test
    fun `snowflake with custom lines replaces default stub`() {
        val rc = TorCard.torrc(bridge = "snowflake", bridgeLines = lines.take(1))
        assertTrue("custom snowflake line", "Bridge obfs4 1.2.3.4:443" in rc)
        assertFalse("default stub gone", "192.0.2.3:1" in rc)
    }
}
