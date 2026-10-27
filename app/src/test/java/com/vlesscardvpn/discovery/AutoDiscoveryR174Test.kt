package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.tor.TorConnectionPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoDiscoveryR174Test {
    @Test fun batteryOfferOnlyAfterConnectedOnSupportedAndroid() {
        assertTrue(VpnBatteryPromptPolicy.shouldOffer(23,true,false,false,false))
        assertFalse(VpnBatteryPromptPolicy.shouldOffer(22,true,false,false,false))
    }
    @Test fun batteryOfferNeverBlocksPendingConnection() {
        assertFalse(VpnBatteryPromptPolicy.shouldOffer(33,false,false,false,false))
    }
    @Test fun batteryOfferDoesNotAskAlreadyExemptUser() {
        assertFalse(VpnBatteryPromptPolicy.shouldOffer(33,true,true,false,false))
    }
    @Test fun batteryOfferIsNotRepeatedAfterDismissalOrReconnect() {
        assertFalse(VpnBatteryPromptPolicy.shouldOffer(33,true,false,true,false))
    }
    @Test fun batteryOfferDoesNotInterruptDebugE2E() {
        assertFalse(VpnBatteryPromptPolicy.shouldOffer(33,true,false,false,true))
    }
    @Test fun failureRefreshDoesNotDependOnTwelveHourTimestamp() {
        assertTrue(AutoDiscoveryPolicy.refreshDue(true,1000,null))
        assertFalse(AutoDiscoveryPolicy.refreshDue(false,1000,null))
        assertFalse(AutoDiscoveryPolicy.refreshDue(true,2000,1000))
        assertTrue(AutoDiscoveryPolicy.refreshDue(true,61000,1000))
        assertTrue(AutoDiscoveryPolicy.refreshDue(true,0,1000))
    }
    @Test fun refreshStaysBoundedToConfiguredSourcesAndPrioritizesCloudAndMobileWhitelist() {
        val mobile="https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt"
        val sources=listOf("https://one.test/sub",mobile,Subs.CLOUD_WORKS,Subs.CLOUD_WORKS,"https://two.test/sub","https://three.test/sub")
        val selected=AutoDiscoveryPolicy.sources(sources,true)
        assertEquals(4,selected.size);assertEquals(Subs.CLOUD_WORKS,selected[0]);assertEquals(mobile,selected[1])
        assertTrue(selected.all { it in sources });assertEquals(4,selected.distinct().size)
        assertTrue(AutoDiscoveryPolicy.sources(emptyList(),false).isEmpty())
    }
    @Test fun tenSecondStageCannotAcquireAnUnboundedBudget() {
        assertEquals(10000L,AutoDiscoveryPolicy.FAST_MS)
        assertTrue(AutoDiscoveryPolicy.SOURCE_MS<=3500)
        assertTrue(AutoDiscoveryPolicy.RECOVERY_MS<=45000)
        assertEquals(8,ConnectPolicy.FIRST)
    }
    @Test fun mirrorsMatchRealRawGithubHostname() {
        val url="https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt"
        val mirrors=Subs.mirrors(url)
        assertEquals(url,mirrors.first());assertEquals(5,mirrors.size)
        assertTrue(mirrors.any { it.startsWith("https://cdn.jsdelivr.net/") })
        assertTrue(mirrors.any { it.startsWith("https://raw.githack.com/") })
    }
    @Test fun privateQueryUserInfoAndUnrelatedHostsNeverReachPublicMirrors() {
        listOf("https://raw.githubusercontent.com/o/r/main/config?token=private",
            "https://user@raw.githubusercontent.com/o/r/main/config",
            "https://raw.githubusercontent.com/o/r/main/config#private",
            "https://rawXgithubusercontentXcom/o/r/main/config").forEach { assertEquals(listOf(it),Subs.mirrors(it)) }
    }
    @Test fun torSelectionAutomaticallyDiscoversAnUpstreamAndSetsNetworkPreset() {
        val old=Settings(mode=Mode.SERVERS,torBridge="obfs4",torBridgeLines="manual bridge",torMaxLatencyMs=100,myMasks=listOf("manual"))
        val chosen=TorConnectionPolicy.selectTor(old)
        assertEquals(Mode.AUTO,chosen.mode);assertTrue(chosen.torNetworkRecommendations)
        assertTrue(chosen.torAll);assertFalse(chosen.torDirect)
        assertEquals(old.torBridgeLines,chosen.torBridgeLines);assertEquals(100,chosen.torMaxLatencyMs)
        assertEquals(old.myMasks,chosen.myMasks)
    }
    @Test fun firstGoodSourceDoesNotWaitForBlockedMirrorAndCancelsIt() = runBlocking {
        val entered=CompletableDeferred<Unit>();var canceled=false
        val result=AutoSubscriptionFetch.firstUseful(listOf("slow","fast")) { url ->
            if(url=="slow") try {entered.complete(Unit);awaitCancellation()} finally {canceled=true}
            else {entered.await();"configs"}
        }
        assertEquals("configs",result);assertTrue(canceled)
    }
    @Test fun mirrorExceptionDoesNotHideOtherSource() = runBlocking {
        val result=AutoSubscriptionFetch.firstUseful(listOf("bad","good")) { url ->
            if(url=="bad") error("synthetic failure") else "configs"
        }
        assertEquals("configs",result)
    }
    @Test fun emptySourcesNeverBecomeWorkingEvidence() = runBlocking {
        assertNull(AutoSubscriptionFetch.firstUseful<String>(emptyList()) { "unreachable" })
        assertNull(AutoSubscriptionFetch.firstUseful<String>(listOf("a","b")) { null })
    }
    @Test fun onlyTwoDistinctMirrorRequestsAreLaunched() = runBlocking {
        val seen=mutableSetOf<String>()
        assertNull(AutoSubscriptionFetch.firstUseful<String>(listOf("a","a","b","c")) {seen+=it;null})
        assertEquals(setOf("a","b"),seen)
    }
    @Test fun cancellationOfAutoCancelsPendingMirrorWork() = runBlocking {
        val entered=CompletableDeferred<Unit>();var canceled=false
        val job=launch {
            AutoSubscriptionFetch.firstUseful<String>(listOf("slow")) {
                try {entered.complete(Unit);awaitCancellation()} finally {canceled=true}
            }
            fail("a canceled search must not return a candidate")
        }
        entered.await();job.cancelAndJoin();assertTrue(canceled)
    }
}
