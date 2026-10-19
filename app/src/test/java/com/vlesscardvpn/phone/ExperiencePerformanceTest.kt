package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test

class ExperiencePerformanceTest {
    @Test fun progressCannotOverflowOrInventTotals() {
        assertNull(ExperiencePolicy.progress(50, 0))
        assertNull(ExperiencePolicy.progress(50, -1))
        assertEquals(0f, ExperiencePolicy.progress(-5, 10)!!, 0f)
        assertEquals(1f, ExperiencePolicy.progress(100, 10)!!, 0f)
        assertEquals(.5f, ExperiencePolicy.progress(5, 10)!!, 0f)
        assertEquals("Подготавливаем проверку", ExperiencePolicy.counter(99, 0))
    }
    @Test fun decorativeMotionHonorsReduceMotionAndForeground() {
        assertTrue(ExperiencePolicy.motion(true,true,true,1f))
        assertFalse(ExperiencePolicy.motion(true,true,true,0f))
        assertFalse(ExperiencePolicy.motion(true,false,true,1f))
        assertFalse(ExperiencePolicy.motion(true,true,false,1f))
        assertFalse(ExperiencePolicy.motion(true,true,true,Float.NaN))
    }
    @Test fun diagnosticPollingSlowsUnderPressure() {
        assertEquals(10000L,ExperiencePolicy.sampleMs(false,true))
        assertEquals(30000L,ExperiencePolicy.sampleMs(true,true))
        assertEquals(30000L,ExperiencePolicy.sampleMs(false,false))
    }
    @Test fun presetsNeverChangeVpnSecurityOrSubscriptions() {
        val before=Settings(mode=Mode.HYBRID,stealthSocks=true,blockAds=true)
        for (id in listOf("eco","balanced","fast")) {
            val after=DeviceEfficiency.preset(before,id)
            assertEquals(before.mode,after.mode); assertTrue(after.stealthSocks);assertTrue(after.blockAds)
            assertEquals(before,DeviceEfficiency.restore(after,before))
        }
        assertEquals(before,DeviceEfficiency.preset(before,"bad"))
    }
    @Test fun undoDoesNotOverwriteSubsequentSecurityChoices() {
        val before=Settings(powerProfile="fast",oceanMotion=true)
        val after=DeviceEfficiency.apply(before).copy(blockAds=false,mode=Mode.SERVERS)
        val undo=DeviceEfficiency.restore(after,before)
        assertEquals("fast",undo.powerProfile); assertTrue(undo.oceanMotion)
        assertFalse(undo.blockAds); assertEquals(Mode.SERVERS,undo.mode)
    }
    @Test fun activeTrafficAndDataSaverLimitEvenFastSearch() {
        val plain=PowerPolicy.evaluate(PowerPolicy.Input(0),PowerPolicy.Options(profile="fast"))
        val traffic=PowerPolicy.evaluate(PowerPolicy.Input(0,activeTraffic=true),PowerPolicy.Options(profile="fast"))
        val saver=PowerPolicy.evaluate(PowerPolicy.Input(0,dataSaver=true),PowerPolicy.Options(profile="fast"))
        assertTrue(traffic.realParallel<plain.realParallel); assertFalse(traffic.allowed)
        assertEquals(2,saver.realParallel);assertFalse(saver.allowed)
    }
}
