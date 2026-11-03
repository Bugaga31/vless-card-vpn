package com.vlesscardvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyChargeWorkR157Test {
    @Test
    fun `charging does not override quiet screen`() {
        val plan = PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false), PowerPolicy.Options())
        assertFalse(plan.allowed)
        assertFalse(plan.allowLight)
        assertTrue(plan.reason.contains("Экран"))
        assertEquals(16, plan.tcpParallel)
        assertEquals(2, plan.realParallel)
    }

    @Test
    fun `fast profile cannot override quiet screen`() {
        val plan = PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false), PowerPolicy.Options(profile = "fast"))
        assertFalse(plan.allowed)
        assertFalse(plan.allowLight)
        assertEquals(16, plan.tcpParallel)
        assertEquals(2, plan.realParallel)
    }

    @Test
    fun `hot or low memory still wins on the charger`() {
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false, thermal = 3), PowerPolicy.Options()).allowed)
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, screen = false, lowMemory = true), PowerPolicy.Options()).allowed)
    }

    @Test
    fun `metered network still gates the charging window`() {
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, metered = true), PowerPolicy.Options()).allowed)
        assertTrue(PowerPolicy.evaluate(PowerPolicy.Input(0, charging = true, metered = true), PowerPolicy.Options(wifiOnly = false)).allowed)
    }

    @Test
    fun `screen off without charging stays quiet`() {
        val plan = PowerPolicy.evaluate(PowerPolicy.Input(0, screen = false), PowerPolicy.Options())
        assertFalse(plan.allowed)
        assertTrue(plan.reason.contains("Экран"))
    }
}
