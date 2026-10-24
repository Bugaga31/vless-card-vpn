package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

/** R161: «жёстко и быстро» — parallelism caps stay within PowerPolicy on every phone. */
class HardSearchR161Test {
    @Test fun capNeverExceedsPolicyLimit() {
        assertEquals(1, PowerPolicy.cap(64, 1))
        assertEquals(32, PowerPolicy.cap(64, 32))
        assertEquals(64, PowerPolicy.cap(64, 96))
    }
    @Test fun lagGuardShrinksRealParallelism() {
        val lag = PowerPolicy.evaluate(PowerPolicy.Input(1_000L, battery = 80, recentLag = true), PowerPolicy.Options())
        assertTrue("lag keeps parallelism tiny", lag.realParallel <= 4)
        val ok = PowerPolicy.evaluate(PowerPolicy.Input(1_000L, battery = 80), PowerPolicy.Options())
        assertTrue(ok.realParallel >= lag.realParallel)
    }
}
