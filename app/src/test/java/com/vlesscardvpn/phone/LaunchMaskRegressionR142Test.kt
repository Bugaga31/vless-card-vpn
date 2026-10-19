package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.*
import org.junit.Assert.*
import org.junit.Test

class LaunchMaskRegressionR142Test {
    @Test fun everyForegroundVisitShowsIntroAgain() {
        val c = LaunchCycle(); val first = c.enter(true)
        assertTrue(first.visible); c.dismiss(first.serial)
        assertFalse(c.enter(true).visible) // recomposition / repeated start is not another visit
        c.leave(false)
        val second = c.enter(true)
        assertTrue(second.visible); assertTrue(second.serial > first.serial)
        c.dismiss(first.serial) // an old timer cannot dismiss the new visit
        assertTrue(c.frame.visible)
    }
    @Test fun rotationDoesNotReplayDismissedIntro() {
        val c = LaunchCycle(); c.dismiss(c.enter(true).serial)
        c.leave(true); assertFalse(c.enter(true).visible)
    }
    @Test fun settingOffIsRespectedAndCanBeReenabled() {
        val c = LaunchCycle(); assertFalse(c.enter(false).visible)
        c.leave(false); assertTrue(c.enter(true).visible)
    }
    @Test fun newActivityCycleIsNotOncePerInstall() {
        assertTrue(LaunchCycle().enter(true).visible)
        assertTrue(LaunchCycle().enter(true).visible)
    }
    private val tls = Server("test", "vless", "example.com", 443, "test", security="tls", pcs="cert-pin")
    @Test fun newCoverVariantsNeverReplaceRealitySniOrUnpinnedTls() {
        for (d in listOf("vk.com","ya.ru","yandex.ru","vkvideo.ru")) {
            val m = Masks.byId("vc.sni.$d.balanced")!!
            assertEquals("tlshello",m.packets);assertEquals(0,m.mss);assertEquals(0,Masks.cost(m))
            assertTrue(Masks.compatible(m,tls))
            assertFalse(Masks.compatible(m,tls.copy(pcs="")))
            assertFalse(Masks.compatible(m,tls.copy(security="reality")))
        }
    }
    @Test fun allCandidateSourcesMustRespectUserFilters() {
        val m = Masks.byId("vc.sni.vk.com.balanced")!!
        assertTrue(MaskSearchPolicy.allowed(m,tls,Settings(),true,true,true))
        assertFalse(MaskSearchPolicy.allowed(m,tls,Settings(maskFamilies=listOf("plain")),true,true,true))
        assertFalse(MaskSearchPolicy.allowed(m,tls,Settings(maskFps=listOf("firefox")),true,true,true))
        assertTrue(MaskSearchPolicy.allowed(Masks.DEFAULT,tls,Settings(maskFamilies=listOf("plain")),false,false,false))
    }
    @Test fun customMasksRespectFamilyAndFingerprintFilters() {
        val custom = Masks.byId("vc.sni.vk.com.balanced")!!.copy(id="my:balanced", custom=true)
        assertTrue(MaskSearchPolicy.allowed(custom,tls,Settings(maskFamilies=listOf("my")),true,true,true))
        assertFalse(MaskSearchPolicy.allowed(custom,tls,Settings(maskFamilies=listOf("brand")),true,true,true))
        assertFalse(MaskSearchPolicy.allowed(custom,tls,Settings(maskFps=listOf("firefox")),true,true,true))
    }
    @Test fun missingTpwsNeverLeaksThroughFreshOrCloudCandidates() {
        val m=Masks.ALL.first { it.dpi.startsWith("TPWS#") }
        assertFalse(MaskSearchPolicy.allowed(m,tls,Settings(),true,false,true))
        assertFalse(MaskSearchPolicy.allowed(m,tls,Settings(),false,true,true))
        assertTrue(MaskSearchPolicy.allowed(m,tls,Settings(),true,true,true))
    }
    @Test fun currentDpiMaskRequiresItsResolvedEngine() {
        val current = Masks.byId("vc.sni.vk.com.balanced")!!.copy(dpi=Masks.CURRENT_DPI)
        assertFalse(MaskSearchPolicy.allowed(current,tls,Settings(),true,true,false))
        assertTrue(MaskSearchPolicy.allowed(current,tls,Settings(),true,true,true))
    }
    @Test fun maskStatsNeverFallBackAcrossPhysicalNetworks() {
        val stats = mapOf("Wi-Fi" to mapOf("old" to MaskStat(9, 0)))
        assertTrue(MaskSearchPolicy.statsForNetwork(stats,"Wi-Fi · WIFI_new").isEmpty())
        assertEquals(MaskStat(9, 0),MaskSearchPolicy.statsForNetwork(stats,"Wi-Fi")["old"])
    }
    @Test fun oldLuckySuccessDoesNotOutrankUnknownAfterFailures() {
        assertEquals(2,MaskSearchPolicy.bucket(MaskStat(1,20)))
        assertEquals(1,MaskSearchPolicy.bucket(MaskStat(1,0)))
        assertEquals(0,MaskSearchPolicy.bucket(MaskStat(4,0)))
        assertEquals(1,MaskSearchPolicy.bucket(null))
    }
    @Test fun videoThroughputBeatsFastHtmlAndHeavyRecipes() {
        val m=Masks.DEFAULT
        val slow=Probe(50,true,true,kbps=20000,ytKbps=700)
        val fast=Probe(130,true,true,kbps=20000,ytKbps=20000)
        assertTrue(MaskSearchPolicy.cost(m,fast) < MaskSearchPolicy.cost(m,slow))
        val heavy=Masks.byId("vc.mss120")!!
        assertTrue(MaskSearchPolicy.cost(m,fast) < MaskSearchPolicy.cost(heavy,fast))
        assertEquals(Int.MAX_VALUE,MaskSearchPolicy.cost(m,Probe(0,null,null)))
    }
    @Test fun unmeasuredSpeedDoesNotBeatMeasuredFastVideoByDefault() {
        val m=Masks.DEFAULT
        assertTrue(MaskSearchPolicy.cost(m,Probe(100,true,true,kbps=10000,ytKbps=20000)) <
            MaskSearchPolicy.cost(m,Probe(50,true,null)))
    }
    @Test fun unknownVideoResultIsNotInventedSuccessOrFailure() {
        val m=Masks.DEFAULT
        val unknown=Probe(150,true,null,kbps=10000,ytKbps=100)
        assertEquals(MaskSearchPolicy.cost(m,unknown.copy(ytKbps=0)),MaskSearchPolicy.cost(m,unknown))
        assertTrue(MaskSearchPolicy.cost(m,unknown.copy(ytOk=false)) > MaskSearchPolicy.cost(m,unknown))
    }
}
