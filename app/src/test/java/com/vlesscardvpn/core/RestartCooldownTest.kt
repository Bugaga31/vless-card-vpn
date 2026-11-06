package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RestartCooldownTest {
    @Test fun firstRestartIsNeverSuppressedAfterBoot() {
        assertTrue(RestartCooldown.hardDue(1000,-1)); assertTrue(RestartCooldown.engineDue(1000,-1))
        assertFalse(RestartCooldown.recentNetwork(1000,-1))
    }
    @Test fun deadlinesUseElapsedTimeAndHandleBackwardSamples() {
        assertFalse(RestartCooldown.hardDue(299999,0)); assertTrue(RestartCooldown.hardDue(300000,0))
        assertFalse(RestartCooldown.engineDue(59999,0)); assertTrue(RestartCooldown.engineDue(60000,0))
        assertTrue(RestartCooldown.recentNetwork(89999,0)); assertFalse(RestartCooldown.recentNetwork(90000,0))
        assertTrue(RestartCooldown.hardDue(5,10)); assertFalse(RestartCooldown.recentNetwork(5,10))
    }
    @Test fun serviceUsesMonotonicTimeAndAvoidsDuplicateRebuilds() {
        val f=listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"),File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first { it.exists() }
        val s=f.readText(); val restart=s.substringAfter("private fun hardRestart(").substringBefore("private var telegramRepairAt")
        assertTrue(restart.contains("hardRestartJob?.isActive == true"))
        assertTrue(restart.contains("SystemClock.elapsedRealtime()"))
        assertTrue(restart.contains("hardRestartJob = scope.launch"))
        assertTrue(s.contains("netChangedAt = android.os.SystemClock.elapsedRealtime()"))
        assertTrue(s.contains("RestartCooldown.engineDue("))
    }
}
