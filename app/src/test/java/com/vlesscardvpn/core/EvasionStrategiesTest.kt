package com.vlesscardvpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессионные тесты движка стратегий стелса.
 */
class EvasionStrategiesTest {

    @Test
    fun cascadePlanHasNoMetaStrategyAndIsOrdered() {
        val plan = EvasionStrategies.cascadePlan()
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.none { it == EvasionStrategies.Strategy.AUTO_CASCADE })
        assertEquals(EvasionStrategies.Strategy.ZAPRET_GHOST, plan.first())
        assertEquals(EvasionStrategies.Strategy.TURBO_REALITY, plan.last())
    }

    @Test
    fun autoSettingResolvesToCascadeWithGhostParams() {
        assertEquals(EvasionStrategies.Strategy.AUTO_CASCADE, EvasionStrategies.resolveActive("auto"))
        assertEquals(EvasionStrategies.Strategy.AUTO_CASCADE, EvasionStrategies.resolveActive("AUTO_CASCADE"))
        assertEquals(EvasionStrategies.Strategy.AUTO_CASCADE, EvasionStrategies.resolveActive(""))
        val params = EvasionStrategies.effectiveFragmentParams("auto", "10-20ms")!!
        assertEquals("tlshello", params.first)
        assertEquals("10-20ms", params.second)
    }

    @Test
    fun defaultTlshelloSettingsKeepCurrentBehaviour() {
        assertEquals(EvasionStrategies.Strategy.ZAPRET_GHOST, EvasionStrategies.resolveActive("tlshello"))
        val params = EvasionStrategies.effectiveFragmentParams("tlshello", "10-20ms")!!
        assertEquals("tlshello", params.first)
        assertEquals("10-20ms", params.second)
    }

    @Test
    fun turboRealityDisablesFragmentation() {
        assertNull(EvasionStrategies.effectiveFragmentParams("turbo_reality", "0ms"))
    }

    @Test
    fun unknownIdFallsBackToAutoCascade() {
        assertEquals(
            EvasionStrategies.Strategy.AUTO_CASCADE,
            EvasionStrategies.Strategy.fromId("does_not_exist")
        )
    }

    @Test
    fun fingerprintFollowsActiveStrategy() {
        assertEquals("chrome", EvasionStrategies.activeFingerprint("tlshello"))
        assertEquals("chrome", EvasionStrategies.activeFingerprint("auto"))
        assertEquals("safari", EvasionStrategies.activeFingerprint("white_ru"))
        assertEquals("randomized", EvasionStrategies.activeFingerprint("morph_chaos"))
    }

    @Test
    fun trustedSniPoolIsLowercaseAndNonEmpty() {
        assertTrue(EvasionStrategies.TRUSTED_SNI_LIST.isNotEmpty())
        assertTrue(EvasionStrategies.TRUSTED_SNI_LIST.all { it == it.lowercase() })
        assertEquals(
            EvasionStrategies.TRUSTED_SNI_LIST.size,
            EvasionStrategies.TRUSTED_SNI_LIST.toSet().size
        )
    }
}
