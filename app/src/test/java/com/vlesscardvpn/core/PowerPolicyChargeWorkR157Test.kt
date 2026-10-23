package com.vlesscardvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Charging + screen off is the best window for heavy background work (1.2.45 optimizer). */
class PowerPolicyChargeWorkR157Test {
    private val P = PowerPolicy

    @Test
    fun `charging and screen off allows full parallel work`() {
        val plan = P.evaluate(P.Input(0, charging = true, screen = false), P.Options())
        assertTrue(plan.allowed)
        assertTrue(plan.reason.contains("Зарядка"))
        assertEquals(64, plan.tcpParallel)
        assertEquals(12, plan.realParallel)
        assertTrue(plan.allowLight)
        assertFalse(plan.deferTraffic)
    }

    @Test
    fun `fast profile on the charger gets the fast numbers`() {
        val plan = P.evaluate(P.Input(0, charging = true, screen = false), P.Options(profile = "fast"))
        assertTrue(plan.allowed)
        assertEquals(128, plan.tcpParallel)
    }

    @Test
    fun `hot or low memory still wins on the charger`() {
        assertFalse(P.evaluate(P.Input(0, charging = true, screen = false, thermal = 3), P.Options()).allowed)
        assertFalse(P.evaluate(P.Input(0, charging = true, screen = false, lowMemory = true), P.Options()).allowed)
    }

    @Test
    fun `metered network still gates the charging window`() {
        assertFalse(P.evaluate(P.Input(0, charging = true, screen = false, metered = true), P.Options()).allowed)
        assertTrue(P.evaluate(P.Input(0, charging = true, screen = false, metered = true), P.Options(wifiOnly = false)).allowed)
    }

    @Test
    fun `screen off without charging stays quiet`() {
        val plan = P.evaluate(P.Input(0, screen = false), P.Options())
        assertFalse(plan.allowed)
        assertTrue(plan.reason.contains("Экран"))
    }
}