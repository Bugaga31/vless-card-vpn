package com.vlesscardvpn

import com.vlesscardvpn.core.OceanPolicy as O
import com.vlesscardvpn.core.ProbeTasks
import com.vlesscardvpn.model.Settings
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.FutureTask

class OceanEnergyRegressionR137Test {
    private fun enabled(motion: Boolean = true, foreground: Boolean = true, saver: Boolean = false,
        battery: Int = 80, charging: Boolean = false, temp: Double = 30.0, thermal: Int = 0, scale: Float = 1f) =
        O.animate(motion, foreground, saver, battery, charging, temp, thermal, scale)
    @Test fun coreVersionBeforeUseDoesNotLoadNativeCode() {
        assertTrue(com.vlesscardvpn.core.XrayCore.version().contains("26.9.30"))
    }
    @Test fun seaDoesNotAnimateInBackgroundSaverOrReducedMotion() {
        assertTrue(enabled())
        assertFalse(enabled(motion = false)); assertFalse(enabled(foreground = false))
        assertFalse(enabled(saver = true)); assertFalse(enabled(scale = 0f))
        assertEquals(50L, O.FRAME_MS)
    }
    @Test fun batteryAndChargingThermalLimitsProtectAnimation() {
        assertFalse(enabled(battery = 20)); assertTrue(enabled(battery = 21))
        assertTrue(enabled(battery = 10, charging = true))
        assertFalse(enabled(temp = 41.0)); assertFalse(enabled(temp = 39.0, charging = true))
        assertFalse(enabled(thermal = 2)); assertFalse(enabled(thermal = 6))
        assertTrue(enabled(battery = -1, temp = 0.0, thermal = -1))
    }
    @Test fun wavesStayWithinCanvasAndMoveSmoothly() {
        for (layer in 0..3) for (i in 0..80) {
            val x = i / 80.0
            assertTrue(O.y(x, 0.0, layer) in .50.. .92)
            assertTrue(kotlin.math.abs(O.y(x, .05, layer) - O.y(x, 0.0, layer)) < .001)
            assertEquals(O.y(x, 0.0, layer), O.y(x, 720720.0, layer), .0000001)
        }
        assertNotEquals(O.y(.4, 0.0, 1), O.y(.4, 8.0, 1), .00001)
    }
    @Test fun seaSettingsRoundTripWithoutResettingOtherPreferences() {
        val s = Settings(oceanBackground = false, oceanMotion = false, theme = "amoled", turbo = false)
        assertEquals(s, Settings.fromJson(s.toJson()))
        assertTrue(Settings.fromJson(org.json.JSONObject()).oceanBackground)
    }
    @Test fun timeoutCancelsUnfinishedChildAndReturnsFallback() {
        val pending = FutureTask { 42 }
        assertEquals(-1, ProbeTasks.await(pending, 15, -1))
        assertTrue(pending.isCancelled)
    }
    @Test fun successfulAndFailedChildrenReturnCorrectResults() {
        val done = FutureTask { 42 }.apply { run() }
        assertEquals(42, ProbeTasks.await(done, 50, -1))
        assertEquals(-1, ProbeTasks.await<Int>(null, 50, -1))
        val broken = FutureTask<Int> { error("test failure") }.apply { run() }
        assertEquals(-1, ProbeTasks.await(broken, 50, -1))
    }
    @Test fun cancellationClosesPendingButDoesNotAlterFinishedChildren() {
        val pending = FutureTask { 1 }; val done = FutureTask { 2 }.apply { run() }
        ProbeTasks.cancel(listOf(pending, done))
        assertTrue(pending.isCancelled); assertFalse(done.isCancelled); assertEquals(2, done.get().toInt())
    }
    @Test fun interruptedParentDoesNotSwallowCancellation() {
        val pending = FutureTask { 1 }; val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val started = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try { ProbeTasks.await(pending, 10000, -1) }
            catch (_: InterruptedException) { interrupted.set(Thread.currentThread().isInterrupted) }
        }.apply { start() }
        started.await(); thread.interrupt(); thread.join(2000)
        assertFalse(thread.isAlive); assertTrue(interrupted.get())
    }
}
