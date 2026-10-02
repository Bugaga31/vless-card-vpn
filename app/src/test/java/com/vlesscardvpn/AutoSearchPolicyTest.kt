package com.vlesscardvpn
import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test
class AutoSearchPolicyTest {
    @Test fun quickSearchBudgetIsBounded() {
        assertEquals(60000L, AutoSearchPolicy.DEADLINE_MS)
        assertEquals(6, AutoSearchPolicy.SAVED_ATTEMPTS)
        assertEquals(24, AutoSearchPolicy.MAX_CANDIDATES)
    }
    private fun node(n: Int) = VlessConfig(name = "Node $n", address = "n$n.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "example.org")
    @Test fun failedTcpIsNotDiscardedAndGetsAlternativeFirst() {
        val p = AutoSearchPolicy.plan(listOf(node(1) to -1))
        assertEquals(5, p.size); assertEquals(RouteProfile.BYEDPI, p.first().profile)
    }
    @Test fun firstRoundTestsDifferentNodesBeforeAnotherProfile() {
        val p = AutoSearchPolicy.plan((1..12).map { node(it) to it })
        assertEquals(36, p.size); assertEquals(12, p.take(12).map { it.config.address }.distinct().size)
        assertTrue(p.take(12).all { it.profile == RouteProfile.COMPATIBLE })
    }
    @Test fun knownProfileTakesPrecedenceEvenWithFailedPing() {
        val c = node(1)
        val p = AutoSearchPolicy.plan(listOf(c to -1), mapOf(AutoConnectPolicy.identity(c) to "FRAGMENT"))
        assertEquals(RouteProfile.FRAGMENT, p.first().profile)
    }
    @Test fun favoritesComeFirstButBadPingDoesNotExcludeOthers() {
        val p = AutoSearchPolicy.plan(listOf(node(1) to 1, node(2).copy(isFavorite = true) to -1))
        assertTrue(p.first().config.isFavorite); assertEquals(2, p.map { it.config.address }.distinct().size)
    }
    @Test fun triedProfileDoesNotExcludeOtherProfilesOfSameNode() {
        val initial = AutoSearchPolicy.plan(listOf(node(1) to 12))
        val retry = AutoSearchPolicy.plan(listOf(initial.first().config to 12), tried = setOf(initial.first().key))
        assertEquals(4, retry.size); assertFalse(retry.any { it.profile == RouteProfile.COMPATIBLE })
    }
    @Test fun repeatedConfigDoesNotConsumeExtraAttempts() {
        val c = node(1); assertEquals(5, AutoSearchPolicy.plan(listOf(c to 1, c to -1)).size)
    }
    @Test fun unsupportedPlaintextCannotEnterPlan() {
        assertTrue(AutoSearchPolicy.plan(listOf(node(1).copy(security = "none") to 1)).isEmpty())
    }
    @Test fun planIsBoundedAndKeysDoNotRevealCredentials() {
        val p = AutoSearchPolicy.plan((1..100).map { node(it) to it })
        assertEquals(36, p.size); assertFalse(p.first().key.contains(p.first().config.uuid))
    }
    private fun report(youtube: Boolean = false, telegram: Boolean = false, latency: Int = 100) =
        TunnelHealthReport(listOf(TunnelProbe("Cloudflare", latency, 204)) +
            (if (youtube) listOf(TunnelProbe("YouTube · HTTPS", latency, 204)) else emptyList()) +
            (if (telegram) listOf(TunnelProbe("Telegram · веб", latency, 200, 200)) else emptyList()))
    @Test fun partialRequiresActualHttpsAndIsNotFullSuccess() {
        assertFalse(AutoSearchPolicy.betterPartial(TunnelHealthReport(), null))
        assertTrue(AutoSearchPolicy.betterPartial(report(), null))
        assertFalse(AutoSearchPolicy.betterPartial(report(true, true), null))
    }
    @Test fun moreConfirmedServicesRankAboveFasterGenericHttps() {
        assertTrue(AutoSearchPolicy.betterPartial(report(youtube = true, latency = 900), report(latency = 10)))
        assertFalse(AutoSearchPolicy.betterPartial(report(latency = 10), report(youtube = true, latency = 900)))
    }
    @Test fun equalServiceCoverageUsesProvenLatency() {
        assertTrue(AutoSearchPolicy.betterPartial(report(latency = 50), report(latency = 90)))
        assertFalse(AutoSearchPolicy.betterPartial(report(latency = 120), report(latency = 90)))
    }
    @Test fun reachableTcpStillGetsByeDpiInSecondRound() {
        val p = AutoSearchPolicy.plan((1..12).map { node(it) to it })
        assertTrue(p.drop(12).take(12).all { it.profile == RouteProfile.BYEDPI })
    }
    @Test fun fullyExhaustedNodesCannotStarveFreshNodes() {
        val all = (1..13).map { node(it) to it }
        val tried = all.take(12).flatMap { AutoSearchPolicy.plan(listOf(it)) }.map { it.key }.toSet()
        val next = AutoSearchPolicy.plan(all, tried = tried)
        assertEquals(5, next.size)
        assertTrue(next.all { it.config.address == "n13.example.org" })
    }
    @Test fun telegramOnlyHttpsCanBeRetainedWithoutClaimingYoutubeWorks() {
        val r = TunnelHealthReport(listOf(TunnelProbe("Telegram · веб", 90, 200, 200)))
        assertTrue(r.internet); assertFalse(r.youtube); assertFalse(r.preferredServices)
        assertTrue(AutoSearchPolicy.betterPartial(r, null))
    }

    @Test fun repeatedlyFailedNodesDoNotAlwaysDisplaceUntestedNodes() {
        val p = AutoSearchPolicy.plan(listOf(node(1).copy(failureCount = 3) to 1, node(2) to -1))
        assertEquals("n2.example.org", p.first().config.address)
        assertEquals("n2.example.org", AutoConnectPolicy.rank(listOf(node(1).copy(failureCount = 3), node(2))).first().address)
    }
    @Test fun importedRowsKeepCanonicalIdFavoritesAndFailureHistory() {
        val old = node(1).copy(id = "saved-id", isFavorite = true, failureCount = 3)
        val fresh = old.copy(id = "parser-id", isFavorite = false, failureCount = 0)
        val result = AutoSearchPolicy.importedRows(listOf(old), listOf(fresh))
        assertEquals(listOf(old), result)
    }
    @Test fun unpersistedImportsCannotCreatePhantomRouteCandidates() {
        assertTrue(AutoSearchPolicy.importedRows(listOf(node(1)), listOf(node(2))).isEmpty())
    }

}
