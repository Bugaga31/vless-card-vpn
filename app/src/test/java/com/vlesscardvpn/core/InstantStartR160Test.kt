package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import org.junit.Assert.*
import org.junit.Test

/** R160: instant start — warm-up plan is ordered most-wanted first and bounded; search parallelism is capped by policy. */
class InstantStartR160Test {
    @Test fun warmupPutsFirstScreensFirst() {
        val list = Warmup.plan(Settings())
        assertTrue("youtube first", list.first() == "www.youtube.com")
        assertTrue("discord in first half", list.indexOf("gateway.discord.gg") < list.size / 2 + 1)
    }

    @Test fun warmupPlanIsPureAndStable() {
        val a = Warmup.plan(Settings(services = listOf("youtube", "discord")))
        val b = Warmup.plan(Settings(services = listOf("youtube", "discord")))
        assertEquals(a, b)
        assertTrue("bounded 14", a.size <= 14)
        assertTrue("no empty labels", a.all { it.split(".").size >= 2 })
    }

    @Test fun powerCapBoundsSearchParallelism() {
        assertEquals(24, PowerPolicy.cap(24, 96))
        assertEquals(16, PowerPolicy.cap(24, 16))
        assertEquals(2, PowerPolicy.cap(24, 2))
        assertEquals(1, PowerPolicy.cap(24, 0))
    }
}
