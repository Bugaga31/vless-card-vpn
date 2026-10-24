package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.Masks
import org.junit.Assert.*
import org.junit.Test

class AutoRoutePolicyTest {
    private val now = 1000000L
    private val server = Server("test", "vless", "node.invalid", 443, "test", security="reality", fp="chrome")
    private fun state(speed: Int = 1000) = ServerState(realMs=40,bigOk=true,maskId="chrome.h4",
        kbps=speed,speedMask="chrome.h4",speedNetwork="wifi",speedCheckedAt=now)

    @Test fun lowLatencyButSlowDownloadContinuesExploration() {
        assertTrue(AutoRoutePolicy.needsFaster(state(120),"wifi",now))
        assertFalse(AutoRoutePolicy.needsFaster(state(8000),"wifi",now))
    }
    @Test fun wrongNetworkOrOldSampleCannotStopExploration() {
        assertTrue(AutoRoutePolicy.needsFaster(state(80000),"cell",now))
        assertTrue(AutoRoutePolicy.needsFaster(state(80000),"wifi",now+AutoSelection.REFRESH_MS))
    }
    @Test fun automaticLaunchKeepsExactMeasuredMaskEvenIfRotationEnabled() {
        assertEquals("chrome.h4",AutoRoutePolicy.launchMask(true,true,"chrome.h4",listOf("chrome.n")){error("random Auto mask")})
    }
    @Test fun nativeWinningRouteIsNotReplacedWithHistoricalMask() {
        assertEquals("",AutoRoutePolicy.launchMask(true,true,"",listOf("chrome.h4")){error("must keep native")})
    }
    @Test fun explicitManualRotationStillWorks() {
        assertEquals("chrome.n",AutoRoutePolicy.launchMask(false,true,"chrome.h4",listOf("chrome.n")){it.first()})
        assertEquals("chrome.h4",AutoRoutePolicy.launchMask(false,false,"chrome.h4",listOf("chrome.n")){error("rotation disabled")})
    }
    @Test fun trialsAreBoundedCompatibleAndDoNotRepeatNativeChrome() {
        val trials=AutoRoutePolicy.maskTrials(server,"chrome.h4",Settings())
        assertEquals(listOf("","chrome.hello.fast"),trials)
        assertTrue(trials.filter { it.isNotEmpty() }.all { Masks.compatible(Masks.byId(it)!!,server) })
        assertEquals(listOf("chrome.hello.fast"),AutoRoutePolicy.maskTrials(server,"chrome.n",Settings()))
    }
    @Test fun datagramServersAreNotGivenTcpFragmentMasks() {
        assertTrue(AutoRoutePolicy.maskTrials(server.copy(protocol="wireguard"),"",Settings()).isEmpty())
    }
    @Test fun userFingerprintFilterIsRespectedForOptionalFragment() {
        val trials=AutoRoutePolicy.maskTrials(server,"chrome.h4",Settings(maskFps=listOf("firefox")))
        assertFalse("chrome.hello.fast" in trials)
    }
    @Test fun onlySuccessfulFullFasterDownloadChangesMask() {
        assertFalse(AutoRoutePolicy.improved(state(),"wifi",now,Probe(10,null,null,kbps=9000)))
        assertFalse(AutoRoutePolicy.improved(state(),"wifi",now,Probe(10,false,null,kbps=9000)))
        assertFalse(AutoRoutePolicy.improved(state(),"wifi",now,Probe(10,true,null,kbps=0)))
        assertFalse(AutoRoutePolicy.improved(state(),"wifi",now,Probe(10,true,null,kbps=999)))
        assertTrue(AutoRoutePolicy.improved(state(),"wifi",now,Probe(100,true,null,kbps=9000)))
    }
    @Test fun acceptedMaskAndBandwidthProvenanceChangeAtomically() {
        val old=state().copy(netMasks=mapOf("cell" to "firefox.h4"))
        val result=AutoRoutePolicy.apply(old,"wifi","",now,Probe(100,true,null,kbps=9000))
        assertEquals("",result.maskFor("wifi"));assertEquals("firefox.h4",result.maskFor("cell"))
        assertEquals("",result.speedMask);assertEquals("wifi",result.speedNetwork)
        assertEquals(9000,ServerChoice.measuredSpeed(result,"wifi",now))
        assertEquals(0,ServerChoice.measuredSpeed(result,"cell",now))
    }
    @Test fun migrationAddsRequestedSourceOnceAndKeepsManualSubscriptions() {
        val migrated=Settings.migrate(Settings(rev=73,subscriptions=listOf("https://example.invalid/sub")))
        assertEquals(listOf(Subs.CLOUD_WORKS,"https://example.invalid/sub",Subs.DIMAN),migrated.subscriptions)
        assertEquals(migrated,Settings.migrate(migrated))
    }
    @Test fun laterRemovalAndDisabledAutoUpdateArePreserved() {
        val removed=Settings(rev=Settings.REV,subscriptions=emptyList(),autoUpdateSubs=false)
        assertEquals(removed,Settings.migrate(removed))
        assertFalse(Settings.migrate(removed).autoUpdateSubs)
    }
    @Test fun sameServerWithDifferentMaskRequiresCoreReconfiguration() {
        val old=state()
        val changed=AutoRoutePolicy.apply(old,"wifi","",now,Probe(100,true,null,kbps=9000))
        assertNotEquals(AutoRoutePolicy.configuration(server,old,"wifi"),AutoRoutePolicy.configuration(server,changed,"wifi"))
        assertEquals(AutoRoutePolicy.configuration(server,old,"wifi"),AutoRoutePolicy.configuration(server,old.copy(kbps=9000),"wifi"))
    }
    @Test fun fragmentedConfigurationsCanBeComparedEvenAboveSoftTarget() {
        assertTrue(AutoRoutePolicy.shouldCompareMasks(state(10000),"wifi",now))
        val native=state(10000).copy(maskId="",speedMask="")
        assertFalse(AutoRoutePolicy.shouldCompareMasks(native,"wifi",now))
    }
    @Test fun sourceEnabledByDefaultButNotFalselyLabelledWhitelist() {
        assertTrue(Subs.DIMAN in Settings.DEFAULT_SUBSCRIPTIONS)
        assertFalse(Subs.isWhitelist(Subs.DIMAN))
        assertEquals(listOf(Subs.DIMAN),Subs.mirrors(Subs.DIMAN))
    }
    @Test fun smallBandwidthNoiseCannotFlipTheMask() {
        assertFalse(AutoRoutePolicy.improved(state(10000),"wifi",now,Probe(30,true,null,kbps=10001)))
        assertFalse(AutoRoutePolicy.improved(state(10000),"wifi",now,Probe(30,true,null,kbps=11999)))
        assertTrue(AutoRoutePolicy.improved(state(10000),"wifi",now,Probe(30,true,null,kbps=12000)))
        assertFalse(AutoRoutePolicy.improved(state(100),"wifi",now,Probe(30,true,null,kbps=120)))
        assertTrue(AutoRoutePolicy.improved(state(100),"wifi",now,Probe(30,true,null,kbps=356)))
        assertTrue(AutoRoutePolicy.improved(state(0),"wifi",now,Probe(30,true,null,kbps=100)))
    }

}
