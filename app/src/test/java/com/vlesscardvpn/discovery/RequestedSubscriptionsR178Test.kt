package com.vlesscardvpn

import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.Subs
import org.junit.Assert.*
import org.junit.Test

class RequestedSubscriptionsR178Test {
    private val ids = listOf("0bsk0rfna3rr", "0qjvewd7qynv", "5rnwokp4nqcw", "wfe5og2pse0p", "f02gu3tqgql1", "2sdfe56lna")
    @Test fun splitLinksExistExactlyOnceAndAreOptIn() {
        ids.forEach { id ->
            val entries = Subs.CATALOG.filter { it.url == "https://pozor.bond/sub/$id" }
            assertEquals(1, entries.size)
            assertFalse(entries.single().default)
            assertFalse(entries.single().whitelist)
        }
        assertEquals(Subs.CATALOG.size, Subs.CATALOG.map { it.url }.distinct().size)
        assertTrue(Subs.CATALOG.none { it.url.contains("/status/") || it.url.contains("/blob/") || it.url.indexOf("https://", 1) >= 0 })
    }
    @Test fun fullIsRawAndEnabledButEmptyPremiumIsOptIn() {
        assertTrue(Subs.OVI_FULL in Settings.DEFAULT_SUBSCRIPTIONS)
        assertFalse(Subs.isWhitelist(Subs.OVI_FULL))
        val premium = Subs.CATALOG.single { it.url == "https://raw.githubusercontent.com/OVI-vpn/bs/main/premium.txt" }
        assertFalse(premium.default)
        assertTrue(Subs.mirrors(Subs.OVI_FULL).first() == Subs.OVI_FULL)
    }
    @Test fun upgradePreservesManualLinksAndDisabledAutoRefresh() {
        val manual = "https://example.invalid/personal"
        val old = Settings(rev = 78, subscriptions = listOf(manual), autoUpdateSubs = false)
        val next = Settings.migrate(old)
        assertEquals(listOf(manual, Subs.OVI_FULL), next.subscriptions)
        assertFalse(next.autoUpdateSubs)
        assertEquals(Settings.REV, next.rev)
        assertEquals(next, Settings.migrate(next))
    }
    @Test fun upgradeDoesNotDuplicateFullAndRespectsLaterRemoval() {
        val next = Settings.migrate(Settings(rev = 78, subscriptions = listOf(Subs.OVI_FULL)))
        assertEquals(listOf(Subs.OVI_FULL), next.subscriptions)
        val removed = next.copy(subscriptions = emptyList())
        assertEquals(removed, Settings.migrate(removed))
    }
}
