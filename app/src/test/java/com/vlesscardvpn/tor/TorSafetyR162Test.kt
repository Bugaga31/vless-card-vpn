package com.vlesscardvpn.tor

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorSafetyR162Test {
    private class Control : TorControl() {
        var signals = 0
        var failConnect = false
        override fun connect(cookieHex: String) { if (failConnect) throw IOException("rejected") }
        override fun signalNewNym() { signals++ }
        override fun setExitCountries(countries: List<String>) {}
        override fun close() {}
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("must reject invalid input") } catch (_: IllegalArgumentException) {}
    }
    @Test fun `generate exact embedded configs for native emulator verification`() {
        val output = java.io.File("build/tor-verify-configs").apply { mkdirs() }
        for (mode in TorCard.EMBEDDED_BRIDGES) {
            val lines = if (mode == "none") emptyList() else listOf("Bridge $mode 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567")
            val text = TorCard.torrcEmbedded(
                "/data/user/0/com.vlesscardvpn/files/tor-native-check/data",
                "/data/user/0/com.vlesscardvpn/files/tor-native-check/cookie",
                "@NATIVE_LIB_DIR@", bridge = mode, bridgeLines = lines) + "DisableNetwork 1\n"
            java.io.File(output, "$mode.conf").writeText(text)
            assertTrue(text.contains("DisableNetwork 1"))
        }
    }
    @Test fun `offline rotation does not send NEWNYM`() {
        val r = TorRotation(rotateEveryMs = 1, now = { 0L })
        r.record("de", 100); r.markRotated(0); r.setOnline(false)
        assertFalse(r.shouldRotate(100)); r.setOnline(true); assertTrue(r.shouldRotate(100))
    }
    @Test fun `closed rotator cannot rotate`() {
        val c = Control(); val r = TorRotation(rotateEveryMs = 1, now = { 0L })
        val f = TorRotator(c, r, cookieHex = "01")
        f.connect(); r.record("de", 100); r.markRotated(0); f.close()
        assertFalse(f.tick(100)); assertEquals(0, c.signals)
    }
    @Test fun `failed reconnect invalidates previous connection`() {
        val c = Control(); val r = TorRotation(rotateEveryMs = 1, now = { 0L })
        val f = TorRotator(c, r, cookieHex = "01")
        f.connect(); r.record("de", 100); r.markRotated(0)
        c.failConnect = true; f.connect()
        assertFalse(f.tick(100)); assertEquals(0, c.signals)
    }
    @Test fun `failed control connection is retryable without rotating`() {
        val c = Control(); val r = TorRotation(rotateEveryMs = 1, now = { 0L })
        val f = TorRotator(c, r, cookieHex = "01")
        c.failConnect = true; assertFalse(f.connect())
        c.failConnect = false; assertTrue(f.connect())
        r.record("de", 100); r.markRotated(0)
        assertTrue(f.tick(100)); assertEquals(1, c.signals)
    }
    @Test fun `invalid measurements never become candidates`() {
        val r = TorRotation()
        assertFalse(r.record("de", -1)); assertFalse(r.record("de", 0))
        assertFalse(r.record("zz\r\nSIGNAL SHUTDOWN", 1)); assertNull(r.pick())
    }
    @Test fun `unsupported embedded transport cannot silently become direct Tor`() {
        for (transport in listOf("snowflake", "meek", "invalid")) rejected {
            TorCard.torrcEmbedded("/d", "/c", "/b", bridge = transport)
        }
    }
    @Test fun `embedded bridge lines cannot inject torrc directives`() {
        for (bad in listOf("Bridge obfs4 x\nDisableNetwork 1", "Bridge obfs4 x\rControlPort 0.0.0.0:1", "Bridge obfs4 x\u0000")) rejected {
            TorCard.torrcEmbedded("/d", "/c", "/b", bridge = "obfs4", bridgeLines = listOf(bad))
        }
    }
    @Test fun `external bridge config rejects injected directives too`() {
        rejected { TorCard.torrc(bridge = "obfs4", bridgeLines = listOf("Bridge obfs4 x\nSocksPort 0.0.0.0:9050")) }
    }
    @Test fun `bridge count and size bounded`() {
        rejected { TorCard.torrcEmbedded("/d", "/c", "/b", bridgeLines = List(13) { "Bridge obfs4 x" }) }
        rejected { TorCard.torrcEmbedded("/d", "/c", "/b", bridgeLines = listOf("x".repeat(4097))) }
    }
    @Test fun `control cookie injection rejected before network`() {
        rejected { TorControl(port = 1).connect("00\r\nSIGNAL SHUTDOWN") }
        rejected { TorControl(port = 1).connect("") }
    }
    @Test fun `control commands reject multiline data before network`() {
        rejected { TorControl().command("SIGNAL", "NEWNYM\r\nSIGNAL SHUTDOWN") }
    }
    @Test fun `country values cannot inject control configuration`() {
        rejected { TorControl().setExitCountries(listOf("de\" StrictNodes=1")) }
    }
    @Test fun `authentication rejection closes socket without retaining cookie in error`() {
        ServerSocket(0).use { server ->
            val closed = CountDownLatch(1)
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine()
                    socket.getOutputStream().apply { write("515 Bad authentication\r\n".toByteArray()); flush() }
                    if (input.read() == -1) closed.countDown()
                }
            }.apply { isDaemon = true; start() }
            val c = TorControl(port = server.localPort)
            try { c.connect("AABB"); fail("auth must fail") } catch (e: IOException) {
                assertFalse(e.message.orEmpty().contains("AABB"))
            }
            assertTrue("failed connection must be closed", closed.await(4, TimeUnit.SECONDS))
            c.close(); worker.join(1000)
        }
    }
}
