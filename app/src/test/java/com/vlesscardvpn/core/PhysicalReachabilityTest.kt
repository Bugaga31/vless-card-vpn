package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class PhysicalReachabilityTest {
    @Test fun stuckEndpointLookupsAreBoundedAndCannotStarveFixedWitnesses() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(RouteLiveness.MAX_SERVER_TARGETS)
        val active = AtomicInteger()
        val fixed = RouteLiveness.PHYSICAL_TARGETS.first()
        val check = PhysicalReachability(setOf(fixed)) { host, _, _ ->
            if (host == fixed.first) true else {
                active.incrementAndGet(); started.countDown()
                try { release.await(3, TimeUnit.SECONDS); false } finally { active.decrementAndGet() }
            }
        }
        val endpoints = (1..3).map { "server$it.invalid" to 443 }
        try {
            assertFalse(check.check(endpoints, 10, 20))
            assertTrue(started.await(1, TimeUnit.SECONDS))
            repeat(20) { assertFalse(check.check(endpoints, 10, 20)) }
            assertEquals(3, active.get())
            assertTrue(check.check(listOf(fixed) + endpoints, 10, 500))
        } finally { release.countDown() }
    }

    @Test fun duplicateAndFailedWitnessesDoNotBecomeSuccess() {
        val check = PhysicalReachability(emptySet()) { _, _, _ -> throw IllegalStateException() }
        assertFalse(check.check(listOf("host" to 443, "host" to 443), 10, 500))
        assertFalse(check.check(emptyList(), 10, 500))
    }

    @Test fun recoveryRejectsResultsFromAnOldPathOrCore() {
        val file = listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"), File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first { it.exists() }
        val recovery = file.readText().substringAfter("private fun recoverServerRoute()").substringBefore("private fun heal()")
        assertEquals(2, Regex("Net\\.matches\\(this@TunnelService, observedPath\\)").findAll(recovery).count())
        assertTrue(recovery.indexOf("val observedPath") < recovery.indexOf("val reachable = physicalOk()"))
        assertTrue(recovery.indexOf("Net.matches(this@TunnelService, observedPath)") < recovery.indexOf("if (!reachable)"))
        assertTrue(recovery.contains("core !== observedCore || Tunnel.socks != observedSocks"))
    }
}
