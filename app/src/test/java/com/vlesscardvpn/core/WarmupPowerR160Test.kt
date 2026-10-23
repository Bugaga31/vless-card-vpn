package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import org.junit.Assert.*
import org.junit.Test

class WarmupPowerR160Test {
    @Test fun warmupListDedupedBounded() {
        val list = Warmup.plan(Settings(services = listOf("youtube", "discord", "telegram")))
        assertEquals(list.size, list.distinct().size)
        assertTrue("cap 14", list.size <= 14)
        assertTrue("video first", "www.youtube.com" in list && "i.ytimg.com" in list)
        assertTrue("discord media warmed", "gateway.discord.gg" in list)
        assertTrue("two-label domains only", list.all { it.split(".").size >= 2 })
    }

    @Test fun warmupSocialOnlyWhenRelevant() {
        val ytOnly = Warmup.plan(Settings(services = listOf("youtube")))
        assertTrue("no discord when only youtube", ytOnly.none { it.contains("discord") })
        val empty = Warmup.plan(Settings(services = emptyList()))
        assertTrue("default warms social", empty.any { it.contains("discord") })
    }

    @Test fun lagGuardPausesBackgroundWork() {
        val now = 1_000_000L
        val lag = PowerPolicy.evaluate(PowerPolicy.Input(now, battery = 80, recentLag = true), PowerPolicy.Options())
        assertTrue("reason names lag", lag.reason.contains("тормозил"))
        assertFalse("light work paused", lag.allowLight)
        assertTrue("small parallelism", lag.tcpParallel <= 32)
        val ok = PowerPolicy.evaluate(PowerPolicy.Input(now, battery = 80), PowerPolicy.Options())
        assertTrue(ok.reason.contains("Готов"))
    }
}