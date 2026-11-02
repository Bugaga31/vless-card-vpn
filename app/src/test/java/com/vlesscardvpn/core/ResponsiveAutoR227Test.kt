package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ResponsiveAutoR227Test {
    @Test fun defaultRangeIncludesThreeSecondsAndFasterThanMinimum() {
        val r = AutoLatencyRange.Range()
        assertEquals(100, r.min); assertEquals(3000, r.max)
        assertTrue(r.accepts(true, 3000, true, 3000))
        assertTrue(r.accepts(true, 1, true, 20))
        assertFalse(r.accepts(true, 3001, true, 100))
        assertFalse(r.accepts(true, 100, true, 3001))
    }
    @Test fun widerLatencyDoesNotApproveFailedOrUnknownChecks() {
        val r = AutoLatencyRange.Range()
        assertFalse(r.accepts(false, 100, true, 100))
        assertFalse(r.accepts(true, 100, null, 100))
        assertFalse(r.accepts(true, 100, false, 100))
        assertFalse(r.accepts(true, 0, true, 100))
    }
    @Test fun customRangeStillWorks() {
        assertTrue(AutoLatencyRange.Range(100, 800).accepts(true, 800, true, 800))
        assertFalse(AutoLatencyRange.Range(100, 800).accepts(true, 801, true, 800))
    }
    @Test fun automaticCoachRunsWithUiOrVpnOrSearch() {
        for (ui in listOf(false, true)) for (vpn in listOf(false, true)) for (search in listOf(false, true))
            assertEquals(ui || vpn || search, AutomaticCoachPolicy.active(ui, vpn, search))
    }
    @Test fun inventoryDoesNotRunInBackgroundAndIsThrottled() {
        assertFalse(AutomaticCoachPolicy.inventoryDue(false, 300000, -1))
        assertTrue(AutomaticCoachPolicy.inventoryDue(true, 0, -1))
        assertFalse(AutomaticCoachPolicy.inventoryDue(true, 60000, 0))
        assertTrue(AutomaticCoachPolicy.inventoryDue(true, 180000, 0))
        assertTrue(AutomaticCoachPolicy.inventoryDue(true, 0, 100))
    }
    @Test fun chosenConcurrencyIsNotReducedForFirstCohort() {
        for (n in listOf(1, 3, 5, 10)) assertEquals(n, AutomaticCoachPolicy.nativeCohortSize(n))
        assertEquals(5, AutomaticCoachPolicy.nativeCohortSize(0))
    }
    private fun source(name: String) = listOf(File("src/main/java/com/vlesscardvpn/$name"), File("app/src/main/java/com/vlesscardvpn/$name")).first { it.exists() }.readText()
    @Test fun powerUiRequestsNeverWaitForSynchronousSensors() {
        val s = source("core/PhonePower.kt")
        val refresh = s.substringAfter("fun refresh(ctx: Context)").substringBefore("@Synchronized private fun refreshBlocking")
        assertTrue(refresh.contains("getMainLooper()")); assertTrue(refresh.contains("return state.value"))
        assertTrue(s.contains("Channel.CONFLATED")); assertFalse(s.contains("events.post(eventRefresh"))
    }
    @Test fun coachIsProcessOwnedAndDoesNotControlVpn() {
        val s = source("core/DeviceCoach.kt")
        assertTrue(source("VlessApplication.kt").contains("DeviceCoach.startAutomatic(this)"))
        assertFalse(source("MainActivity.kt").contains("DeviceCoach.sample(applicationContext)"))
        assertTrue(s.contains("compareAndSet(false, true)")); assertTrue(s.contains("collectLatest"))
        assertFalse(s.contains("Store.update")); assertFalse(s.contains("TunnelService.start")); assertFalse(s.contains("Actions.autoConnect"))
    }
    @Test fun masksFollowFirstCohortInsteadOfSixNativeBatches() {
        val s = source("core/Actions.kt").substringAfter("private suspend fun doAutoPrepare").substringBefore("suspend fun quickTest")
        assertTrue(s.contains("!continuous || nativeCohorts == 0"))
        assertTrue(s.contains("ranked.take(AutomaticCoachPolicy.nativeCohortSize"))
        assertTrue(s.contains("quickMaskRescue(candidates,net,handle)?.let(firstReady)"))
        assertTrue(s.contains("allowMaskRescue = false"))
    }
    @Test fun pauseOnlyDecorativeAnimationDuringSearch() {
        val s = source("ui/OceanBackground.kt")
        assertTrue(s.contains("!eligible || !foreground || searching"))
        assertFalse(s.contains("Store.update")); assertFalse(s.contains("cancelPrepare"))
    }
}
