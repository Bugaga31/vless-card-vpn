package com.vlesscardvpn.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R160: full tor-rotator algorithm — measure → best country pinned (SETCONF) → fresh circuits (NEWNYM). */
class TorRotatorAlgorithmR160Test {

    /** Control stub recording what the rotator actually sent. */
    private class RecordingControl : TorControl("127.0.0.1", 1) {
        val exitNodes = mutableListOf<String>()
        var newnym = 0
        override fun connect(cookieHex: String) {}
        override fun signalNewNym() { newnym++ }
        override fun setExitCountries(countries: List<String>) { exitNodes += countries.joinToString(",") }
        override fun close() {}
    }

    @Test
    fun `rotation pins the best measured country before NEWNYM`() {
        var t = 1_000_000L
        val rotation = TorRotation(rotateEveryMs = 60_000, thresholdFloorMs = 100, now = { t })
        val control = RecordingControl()
        val rotator = TorRotator(control, rotation, cookieHex = "01")
        rotator.connect()
        rotator.recordExit(200, "nl")
        rotator.recordExit(1000, "de")
        rotation.markRotated(t)
        t += 61_000
        assertTrue(rotator.tick(t))
        assertEquals(listOf("nl"), control.exitNodes) // fastest EMA wins, pinned via SETCONF
        assertEquals(1, control.newnym)
    }

    @Test
    fun `no rotation while cadence gate holds`() {
        val control = RecordingControl()
        val rotation = TorRotation(rotateEveryMs = 600_000, now = { 0 })
        val rotator = TorRotator(control, rotation, cookieHex = "01")
        rotator.connect()
        rotator.recordExit(100, "nl")
        rotation.markRotated(0)
        assertFalse(rotator.tick(1000))
        assertEquals(0, control.newnym)
        assertTrue(control.exitNodes.isEmpty())
    }
}
