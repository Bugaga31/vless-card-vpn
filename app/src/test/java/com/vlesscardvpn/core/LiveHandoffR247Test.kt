package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.2.135: Wi-Fi ⇄ cellular — the running route races the isolated wave, and the wave fits a fresh cellular link. */
class LiveHandoffR247Test {
    private val profile = AutoCheckProfile(setOf("telegram"))
    private val good = Probe(realMs = 320, bigOk = null, ytOk = null, tgOk = true, tgMs = 280)
    private fun source(path: String): String = listOf(File("app/$path"), File(path)).first { it.exists() }.readText()

    @Test fun waveFitsItsWindowAndRunsWhole() {
        assertTrue(AutoHandoffPolicy.PROBE_BUDGET_MS < AutoHandoffPolicy.PROBE_WINDOW_MS)
        assertTrue(AutoHandoffPolicy.PROBE_WINDOW_MS < AutoHandoffPolicy.TOTAL_MS)
        assertEquals(AutoHandoffPolicy.MAX_CANDIDATES, AutoHandoffPolicy.PARALLEL)
        assertTrue(LiveHandoffProbe.BUDGET_MS < AutoHandoffPolicy.PROBE_WINDOW_MS)
    }

    @Test fun onlyAPlainSingleAutomaticRouteIsAttributed() {
        assertTrue(LiveHandoffProbe.eligible(true, 1, chain = false, tor = false, warpChained = false, mux = false))
        assertFalse(LiveHandoffProbe.eligible(false, 1, false, false, false, false))
        assertFalse(LiveHandoffProbe.eligible(true, 2, false, false, false, false))
        assertFalse(LiveHandoffProbe.eligible(true, 1, true, false, false, false))
        assertFalse(LiveHandoffProbe.eligible(true, 1, false, true, false, false))
        assertFalse(LiveHandoffProbe.eligible(true, 1, false, false, true, false))
        assertFalse(LiveHandoffProbe.eligible(true, 1, false, false, false, true))
    }

    @Test fun anotherLearnedMaskOfTheNewNetworkIsNeverOverwritten() {
        val fresh = ServerState(maskId = "chrome.n")
        assertTrue(LiveHandoffProbe.maskAllowed(fresh, "cell", "vc.tfo"))
        assertTrue(LiveHandoffProbe.maskAllowed(fresh.copy(netMasks = mapOf("cell" to "vc.tfo")), "cell", "vc.tfo"))
        assertFalse(LiveHandoffProbe.maskAllowed(fresh.copy(netMasks = mapOf("cell" to "chrome.h4")), "cell", "vc.tfo"))
    }

    @Test fun acceptedSampleIsPublishedForTheNewNetworkOnly() {
        val old = ServerState(maskId = "chrome.n", netMasks = mapOf("wifi" to "vc.tfo"), checkedAt = 1_000, okCount = 2, bigOk = false)
        val next = LiveHandoffProbe.commit(old, good, profile, "cell", "vc.tfo", started = 2_000, now = 3_000)
        assertNotSame(old, next)
        assertEquals("vc.tfo", next.maskFor("cell"))
        assertEquals("vc.tfo", next.maskFor("wifi"))
        assertEquals(320, next.realMs); assertEquals(3_000L, next.checkedAt); assertEquals(3, next.okCount)
        assertEquals(null, next.bigOk)
        assertEquals("cell", next.serviceNetwork)
        assertTrue(next.netEvidence.containsKey("cell|vc.tfo"))
        assertTrue(next.works)
    }

    @Test fun staleFailedOrMisattributedSamplesChangeNothing() {
        val old = ServerState(maskId = "chrome.n", checkedAt = 5_000)
        assertSame(old, LiveHandoffProbe.commit(old, good, profile, "cell", "chrome.n", started = 4_000, now = 6_000))
        val base = ServerState(maskId = "chrome.n")
        assertSame(base, LiveHandoffProbe.commit(base, good.copy(tgOk = false), profile, "cell", "chrome.n", 1, 2))
        assertSame(base, LiveHandoffProbe.commit(base, good.copy(realMs = 0), profile, "cell", "chrome.n", 1, 2))
        val learned = base.copy(netMasks = mapOf("cell" to "chrome.h4"))
        assertSame(learned, LiveHandoffProbe.commit(learned, good, profile, "cell", "chrome.n", 1, 2))
    }

    @Test fun liveLaneAndWiderWaveAreWired() {
        val actions = source("src/main/java/com/vlesscardvpn/core/Actions.kt")
        assertTrue(actions.contains("parallel = AutoHandoffPolicy.PARALLEL, budgetMs = AutoHandoffPolicy.PROBE_BUDGET_MS"))
        assertTrue(actions.contains("liveLane?.invokeOnCompletion { if (liveWinner.get() != null) wave?.cancel() }"))
        val service = source("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")
        assertTrue(service.contains("Actions.quickRecheck(previousCandidates, live)"))
        val probe = service.substringAfter("private suspend fun liveHandoffProbe(").substringBefore("private fun teardown(")
        assertTrue(probe.contains("!handoff.valid(revision) || Net.key(this) != net"))
        assertTrue(probe.contains("LiveHandoffProbe.commit("))
        // a lost UDP datagram on a fresh link no longer fails the live DNS check within 3 s
        assertTrue(service.contains("Tester.dnsViaSocks(port, auth.user, auth.pass, budgetMs = 4500, onCancel = bind)"))
        assertEquals(3, Tester.DNS_HEALTH_QUERIES)
        assertTrue(Tester.DNS_HEALTH_RESEND_MS * (Tester.DNS_HEALTH_QUERIES - 1) < 4500)
    }
}
