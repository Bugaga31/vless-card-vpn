package com.vlesscardvpn.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StandbyProgressR245Test {
    @Test fun successfulReserveCanTriggerNextBoundedPass() {
        val file = listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"), File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first { it.exists() }
        val source = file.readText().substringAfter("private fun prepareStandbySoon()").substringBefore("private var scoutJob")
        assertTrue("a verified increase should trigger another pass without waiting for the next poll", source.contains("StandbyPreparationPolicy.continueAfter"))
        assertTrue("the whole wave must have a bounded deadline", source.contains("withTimeoutOrNull(com.vlesscardvpn.core.StandbyPreparationPolicy.PASS_TIMEOUT_MS)"))
    }
    @Test fun verifiedGrowthContinuesOnlyWhileBelowTarget() {
        assertTrue(StandbyPreparationPolicy.continueAfter(0,1))
        assertTrue(StandbyPreparationPolicy.continueAfter(1,2))
        assertFalse(StandbyPreparationPolicy.continueAfter(2,3))
    }
    @Test fun noProgressOrLostProofStopsTheWave() {
        assertFalse(StandbyPreparationPolicy.continueAfter(0,0))
        assertFalse(StandbyPreparationPolicy.continueAfter(2,1))
        assertFalse(StandbyPreparationPolicy.continueAfter(-1,1))
        assertFalse(StandbyPreparationPolicy.continueAfter(3,4))
    }
    @Test fun failedWaveRetainsCooldownAndNoConcurrentPass() {
        assertFalse(StandbyPreparationPolicy.due(2000,1000,0,false))
        assertFalse(StandbyPreparationPolicy.due(31000,1000,0,true))
        assertTrue(StandbyPreparationPolicy.due(31000,1000,0,false))
    }
    @Test fun serviceFencesEveryPassAndPreservesCancellationAndTarget() {
        val file = listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"), File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first { it.exists() }
        val source = file.readText().substringAfter("private fun prepareStandbySoon()").substringBefore("private var scoutJob")
        assertTrue(source.contains("repeat(com.vlesscardvpn.core.StandbyPreparationPolicy.TARGET)"))
        assertTrue(source.contains("chainEpoch.get()!=sampledEpoch"))
        assertTrue(source.contains("Net.matches(this@TunnelService,sampledNetwork)"))
        assertTrue(source.contains("usedServers.toList()!=servers"))
        assertTrue(source.contains("!isActive"))
        assertTrue(source.contains("catch(e:kotlinx.coroutines.CancellationException) {throw e}"))
    }
}
