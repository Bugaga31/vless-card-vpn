package com.vlesscardvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Charging + screen off is the best window for heavy background work (1.2.45 optimizer). */
class PowerPolicyChargeWorkR157Test {

    @Test
    fun `charging and screen off allows full parallel work`() {
        val plan = PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false), PowerPolicy.Options())
        assertTrue(plan.allowed)
        assertTrue(plan.reason.contains("Зарядка"))
        assertEquals(64, plan.tcpParallel)
        assertEquals(6, plan.realParallel)
        assertTrue(plan.allowLight)
        assertFalse(plan.deferTraffic)
        assertEquals(3000, plan.loopMs)
    }

    @Test
    fun `fast profile on the charger gets the fast numbers`() {
        val plan = PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false), PowerPolicy.Options(profile = "fast"))
        assertTrue(plan.allowed)
        assertEquals(128, plan.tcpParallel)
        assertEquals(12, plan.realParallel)
    }

    @Test
    fun `hot or low memory still wins on the charger`() {
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false, thermal = 3), PowerPolicy.Options()).allowed)
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false, lowMemory = true), PowerPolicy.Options()).allowed)
    }

    @Test
    fun `metered network still gates the charging window`() {
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false, metered = true), PowerPolicy.Options()).allowed)
        assertTrue(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false, metered = true), PowerPolicy.Options(wifiOnly = false)).allowed)
    }

    @Test
    fun `screen off without charging stays quiet`() {
        val plan = PowerPolicy.evaluate(PowerPolicy.Input(0, screen = false), PowerPolicy.Options())
        assertFalse(plan.allowed)
        assertTrue(plan.reason.contains("Экран"))
    }
}