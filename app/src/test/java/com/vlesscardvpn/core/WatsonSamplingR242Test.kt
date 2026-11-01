package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class WatsonSamplingR242Test {
    @Test fun ordinaryReadsReuseCachedSensors() {
        for (age in listOf(0L, 1L, 500L, 9999L)) assertFalse(PhoneSamplingPolicy.due(20_000L + age, 20_000L, false))
    }
    @Test fun expiryAndSystemEventsAlwaysRefresh() {
        assertTrue(PhoneSamplingPolicy.due(30_000, 20_000, false))
        assertTrue(PhoneSamplingPolicy.due(20_001, 20_000, true))
    }
    @Test fun missingOrRegressedClockCannotKeepOldSample() {
        assertTrue(PhoneSamplingPolicy.due(10, -1, false))
        assertTrue(PhoneSamplingPolicy.due(10, 11, false))
    }
    @Test fun optionalAdviceSlowsOnlyItsOwnBackgroundCadence() {
        assertEquals(60_000L, AutomaticCoachPolicy.sampleDelay(true, false))
        assertEquals(120_000L, AutomaticCoachPolicy.sampleDelay(true, true))
        assertEquals(120_000L, AutomaticCoachPolicy.sampleDelay(false, false))
        assertEquals(300_000L, AutomaticCoachPolicy.sampleDelay(false, true))
        assertTrue(AutomaticCoachPolicy.active(false, true, false))
        assertTrue(AutomaticCoachPolicy.active(false, false, true))
    }
}
