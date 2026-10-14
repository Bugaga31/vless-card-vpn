package com.vlesscardvpn

import com.vlesscardvpn.core.PowerPolicy as P
import com.vlesscardvpn.core.PhoneLearning
import com.vlesscardvpn.core.AppWatch
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PowerPolicyRegressionR134Test {
    @Test fun profilesChangeLimitsWithoutBypassingSafety() {
        val i = P.Input(0, battery = 80, temp = 30.0, thermal = 0)
        assertTrue(P.evaluate(i, P.Options(profile = "fast")).tcpParallel > P.evaluate(i, P.Options()).tcpParallel)
        assertTrue(P.evaluate(i, P.Options(profile = "eco")).tcpParallel < P.evaluate(i, P.Options()).tcpParallel)
        assertFalse(P.evaluate(i.copy(saver = true, charging = true), P.Options(profile = "fast")).allowed)
        assertTrue(AppWatch.eco(true, 80, true))
    }
    @Test fun lowBatteryHysteresisAndUnknownReadings() {
        val low = P.evaluate(P.Input(0, battery = 20), P.Options())
        assertFalse(low.allowed)
        assertTrue(P.evaluate(P.Input(10, battery = 25), P.Options(), low.memory).memory.low)
        assertFalse(P.evaluate(P.Input(10, battery = 26), P.Options(), low.memory).memory.low)
        assertTrue(P.evaluate(P.Input(10, battery = -1), P.Options(), low.memory).memory.low)
        assertFalse(P.evaluate(P.Input(10, battery = 5, charging = true), P.Options(), low.memory).memory.low)
    }
    @Test fun chargingHeatGuardAndCriticalCannotBeDisabled() {
        assertTrue(P.evaluate(P.Input(0, temp = 39.0), P.Options()).allowed)
        assertFalse(P.evaluate(P.Input(0, temp = 39.0, charging = true), P.Options()).allowed)
        assertFalse(P.evaluate(P.Input(0, temp = 45.0), P.Options(autoCool = false)).allowed)
        assertFalse(P.evaluate(P.Input(0, thermal = 3), P.Options(autoCool = false)).allowed)
    }
    @Test fun coolingRequiresTwoMinutesAndUnknownDoesNotRelease() {
        val hot = P.evaluate(P.Input(0, temp = 42.0), P.Options()).memory
        val cool = P.evaluate(P.Input(1000, temp = 37.0, thermal = 0), P.Options(), hot).memory
        assertTrue(P.evaluate(P.Input(120999, temp = 37.0, thermal = 0), P.Options(), cool).memory.hot)
        assertFalse(P.evaluate(P.Input(121000, temp = 37.0, thermal = 0), P.Options(), cool).memory.hot)
        val unknown = P.evaluate(P.Input(5000), P.Options(), cool).memory
        assertTrue(unknown.hot); assertEquals(-1L, unknown.coolSince)
        assertTrue(P.evaluate(P.Input(1000000, temp = 37.0, thermal = 0), P.Options(), unknown).memory.hot)
    }
    @Test fun screenQuietIsIndependentAndPauseExpires() {
        assertFalse(P.evaluate(P.Input(0, screen = false), P.Options()).allowed)
        assertTrue(P.evaluate(P.Input(0, screen = false), P.Options(quietScreen = false)).allowed)
        assertFalse(P.evaluate(P.Input(59999), P.Options(), pauseUntil = 60000).allowed)
        assertTrue(P.evaluate(P.Input(60000), P.Options(), pauseUntil = 60000).allowed)
    }
    @Test fun chargingNetworkAndTrafficGatesAreIndependent() {
        assertFalse(P.evaluate(P.Input(0), P.Options(chargeOnly = true)).allowed)
        assertTrue(P.evaluate(P.Input(0, charging = true), P.Options(chargeOnly = true)).allowed)
        assertFalse(P.evaluate(P.Input(0, metered = true), P.Options()).allowed)
        assertTrue(P.evaluate(P.Input(0, metered = true), P.Options(wifiOnly = false)).allowed)
        assertFalse(P.evaluate(P.Input(0, dataSaver = true), P.Options(wifiOnly = false)).allowed)
        assertFalse(P.evaluate(P.Input(0, activeTraffic = true), P.Options()).allowed)
        assertTrue(P.evaluate(P.Input(0, activeTraffic = true), P.Options(protectTraffic = false)).allowed)
    }
    @Test fun requestedConcurrencyIsActuallyHonoredAndNeverZero() {
        assertEquals(2, P.cap(2, 64)); assertEquals(16, P.cap(512, 16)); assertEquals(1, P.cap(0, 16))
        assertEquals(1, P.cap(-1, 0))
        assertEquals(45000L, P.evaluate(P.Input(0, saver = true), P.Options()).healthyMs)
    }
    @Test fun settingsRoundTripAndClampPreserveUserPreferences() {
        val s = Settings(powerProfile = "eco", batteryThreshold = 30, quietScreen = false,
            chargeOnly = true, wifiMaintenance = false, protectActiveTraffic = false, phoneLearning = false, turbo = false)
        assertEquals(s, Settings.fromJson(s.toJson()))
        val bad = Settings.fromJson(JSONObject().put("powerProfile", "invalid").put("batteryThreshold", 999))
        assertEquals("balanced", bad.powerProfile); assertEquals(50, bad.batteryThreshold)
        assertTrue(Settings.fromJson(JSONObject()).quietScreen)
    }
    @Test fun separateNetworkReallyLearnsAndRequiresBothLabels() {
        val m = PhoneLearning(); val x = doubleArrayOf(.9, .3, 1.0, 1.0, 1.0)
        val before = m.predict(x)
        repeat(100) { m.train(x, true) }
        assertTrue(m.predict(x) > before + .2); assertFalse(m.ready())
        repeat(3) { m.train(doubleArrayOf(-.7, 0.0, -1.0, -1.0, -1.0), false) }
        assertTrue(m.ready())
        val copy = PhoneLearning(); copy.restore(m.encode())
        assertEquals(m.predict(x), copy.predict(x), .000000001)
        assertEquals(m.samples, copy.samples)
    }
    @Test fun malformedModelsAndNonFiniteInputsAreRejected() {
        val m = PhoneLearning()
        assertTrue(runCatching { m.restore("junk") }.isFailure)
        assertTrue(runCatching { m.restore(m.encode().split(',').toMutableList().apply { this[3] = "NaN" }.joinToString(",")) }.isFailure)
        assertTrue(runCatching { m.predict(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 0.0)) }.isFailure)
        repeat(1000) { m.train(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0), true) }
        assertTrue(m.predict(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)) in 0.0..1.0)
    }
}
