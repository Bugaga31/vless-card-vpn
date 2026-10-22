package com.vlesscardvpn.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRotatorTest {

    /** Same fake ControlPort shape as TorControlTest, reused for the facade. */
    private class FakeControl {
        lateinit var server: java.net.ServerSocket
        var stopped = false

        fun start() {
            val t = Thread {
                server = java.net.ServerSocket(0)
                val s = server.accept()
                val r = java.io.BufferedReader(java.io.InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val w = s.getOutputStream()
                while (!stopped) {
                    val line = r.readLine() ?: break
                    when {
                        line.startsWith("AUTHENTICATE") -> w.write("250 OK\r\n".toByteArray())
                        line.startsWith("QUIT") -> { w.write("250 closing\r\n".toByteArray()); break }
                        else -> w.write("250 OK\r\n".toByteArray())
                    }
                    w.flush()
                }
                s.close()
            }
            t.isDaemon = true
            t.start()
        }

        fun awaitPort() {
            while (!::server.isInitialized) Thread.sleep(20)
        }
    }

    @Test
    fun `tick silently skips when not due and rotates when due`() {
        val fake = FakeControl().apply { start(); awaitPort() }
        var t = 1_000L
        val rotation = TorRotation(rotateEveryMs = 60_000, now = { t })
        val rotator = TorRotator(TorControl("127.0.0.1", fake.server.localPort), rotation, cookieHex = "01")
        try {
            rotator.connect()
            rotator.recordExit(100, "de")
            assertFalse(rotator.tick(t)) // nothing due yet
            rotation.markRotated(t)
            assertFalse(rotator.tick(t))
            t += 61_000
            assertTrue(rotator.tick(t))
        } finally {
            fake.stopped = true
            fake.server.close()
            Thread.sleep(100)
        }
    }

    @Test
    fun `manual blacklist blocks the country pick`() {
        val rot = TorRotation(now = { 0L })
        rot.record("de", 100)
        rot.blacklistCountry("de", 0L)
        assertEquals(null, rot.pick())
    }

    @Test
    fun `connect failure surfaces as lastError`() {
        val rotator = TorRotator(TorControl("127.0.0.1", 1), cookieHex = "01")
        rotator.connect()
        assertFalse(rotator.tick())
        assertTrue(rotator.lastErrorOr != null)
    }
}
