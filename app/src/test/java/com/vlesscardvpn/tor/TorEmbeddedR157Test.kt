package com.vlesscardvpn.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** torrc of the daemon embedded in the APK: no Termux, private dirs, lyrebird bridges (1.2.46). */
class TorEmbeddedR157Test {
    @Test
    fun `embedded torrc keeps loopback ports cookie and private data dir`() {
        val rc = TorCard.torrcEmbedded("/data/app/files/tor/data", "/data/app/files/tor/cookie",
            "/data/app/lib/arm64", controlPort = 9091, socksPort = 9050)
        assertTrue(rc.contains("SocksPort 127.0.0.1:9050"))
        assertTrue(rc.contains("ControlPort 127.0.0.1:9091"))
        assertTrue(rc.contains("CookieAuthentication 1"))
        assertTrue(rc.contains("CookieAuthFile /data/app/files/tor/cookie"))
        assertTrue(rc.contains("DataDirectory /data/app/files/tor/data"))
    }

    @Test
    fun `obfs4 and webtunnel ride the packaged lyrebird binary`() {
        val obfs = TorCard.torrcEmbedded("/d", "/c", "/n", bridge = "obfs4")
        assertTrue(obfs.contains("ClientTransportPlugin obfs4,webtunnel exec /n/libLyrebird.so"))
        assertTrue(obfs.contains("UseBridges 1"))
        assertFalse(obfs.contains("com.termux"))
        val web = TorCard.torrcEmbedded("/d", "/c", "/n", bridge = "webtunnel")
        assertTrue(web.contains("exec /n/libLyrebird.so"))
    }

    @Test
    fun `plain embedded torrc has no bridge directives and no termux paths`() {
        val rc = TorCard.torrcEmbedded("/d", "/c", "/n")
        assertFalse(rc.contains("UseBridges"))
        assertFalse(rc.contains("ClientTransportPlugin"))
        assertFalse(rc.contains("termux"))
    }

    @Test
    fun `embedded bridges list matches what the APK ships`() {
        assertEquals(listOf("none", "obfs4", "webtunnel"), TorCard.EMBEDDED_BRIDGES)
    }
}
