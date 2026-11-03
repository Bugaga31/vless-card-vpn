package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BackgroundReservesR237Test {
    private fun source(n:String)=listOf(File("src/main/$n"),File("app/src/main/$n")).first{it.exists()}.readText()
    private fun plan(i:PowerPolicy.Input,o:PowerPolicy.Options=PowerPolicy.Options())=PowerPolicy.evaluate(i,o)
    @Test fun emptyPoolStartsImmediately(){assertTrue(StandbyPreparationPolicy.due(1000,null,0,false))}
    @Test fun fullPoolDoesNotProbe(){assertFalse(StandbyPreparationPolicy.due(1000,null,3,false))}
    @Test fun overfullPoolDoesNotProbe(){assertFalse(StandbyPreparationPolicy.due(1000,null,5,false))}
    @Test fun runningWaveIsNeverDuplicated(){assertFalse(StandbyPreparationPolicy.due(1000,null,0,true))}
    @Test fun failedWaveDoesNotRepeatEverySecond(){for(t in listOf(1001L,2000L,30999L))assertFalse(StandbyPreparationPolicy.due(t,1000,0,false))}
    @Test fun missingPoolRetriesAtThirtySeconds(){assertTrue(StandbyPreparationPolicy.due(31000,1000,0,false))}
    @Test fun rollbackDoesNotStrandScout(){assertTrue(StandbyPreparationPolicy.due(999,1000,0,false))}
    @Test fun partialPoolCanBeFilled(){assertTrue(StandbyPreparationPolicy.due(31000,1000,2,false))}
    @Test fun screenOffDoesNotBlockReliabilityReserve(){val p=plan(PowerPolicy.Input(0,battery=80,screen=false));assertFalse(p.allowLight);assertFalse(p.allowReserve);assertTrue(StandbyPreparationPolicy.mayPrepare(true,p))}
    @Test fun cellularDoesNotDependOnOptimizerWifiOnly(){val p=plan(PowerPolicy.Input(0,battery=80,metered=true));assertFalse(p.allowLight);assertTrue(StandbyPreparationPolicy.mayPrepare(true,p))}
    @Test fun chargingQuietDoesNotDisableMissingReserve(){val p=plan(PowerPolicy.Input(0,battery=50,charging=true));assertFalse(p.allowReserve);assertTrue(StandbyPreparationPolicy.mayPrepare(true,p))}
    @Test fun saverDoesNotDisableReliability(){assertTrue(StandbyPreparationPolicy.mayPrepare(true,plan(PowerPolicy.Input(0,battery=80,saver=true))))}
    @Test fun ecoDoesNotDisableReliability(){assertTrue(StandbyPreparationPolicy.mayPrepare(true,plan(PowerPolicy.Input(0,battery=80),PowerPolicy.Options(profile="eco"))))}
    @Test fun thermalSafetyDefersStandbyNotRecovery(){val p=plan(PowerPolicy.Input(0,temp=46.0));assertFalse(StandbyPreparationPolicy.mayPrepare(true,p))}
    @Test fun callDefersOnlyStandby(){assertFalse(StandbyPreparationPolicy.mayPrepare(true,plan(PowerPolicy.Input(0,voiceCall=true))))}
    @Test fun manualModeDoesNotStartAutoScout(){assertFalse(StandbyPreparationPolicy.mayPrepare(false,PowerPolicy.Plan()))}
    @Test fun spareProofWindowMatchesAdmissionCeiling(){assertEquals(300000L,WarmReserveMemory.TTL_MS)}
    @Test fun standbyPassHasBoundedWallTime(){assertEquals(45000L,StandbyPreparationPolicy.PASS_TIMEOUT_MS)}
    @Test fun backgroundWorkNeverChangesUserCeiling(){for(n in listOf(1,3,5,10))assertEquals(n,ProbeConcurrencyPolicy.limit(32,n,PowerPolicy.Plan(allowed=false),false))}
    @Test fun actualServiceCallsPreparation(){val s=source("java/com/vlesscardvpn/vpn/TunnelService.kt");assertTrue(s.contains("Action.PREPARE -> prepareStandbySoon()"));assertTrue(s.contains("Actions.prepareStandby(servers)"));assertFalse(s.contains("Action.PREPARE -> Unit"))}
    @Test fun watchdogRetriesAfterExceptionWithoutScreen(){val s=source("java/com/vlesscardvpn/vpn/TunnelService.kt");assertTrue(s.contains("watchdog cycle failed; retrying"));assertTrue(s.contains("catch(e:kotlinx.coroutines.CancellationException) { throw e }"))}
    @Test fun standbyDoesNotOwnSelection(){val s=source("java/com/vlesscardvpn/core/Actions.kt").substringAfter("suspend fun prepareStandby(").substringBefore("/** New migration");assertFalse(s.contains("selected ="));assertFalse(s.contains("connect("));assertTrue(s.contains("withTimeoutOrNull(StandbyPreparationPolicy.PASS_TIMEOUT_MS)"));assertTrue(s.contains("stopAfter=1"))}
    @Test fun recoveryCancelsOptionalStandby(){val s=source("java/com/vlesscardvpn/vpn/TunnelService.kt").substringAfter("private fun recoverServerRoute()").substringBefore("private fun heal()");assertTrue(s.indexOf("standbyJob?.cancel()")<s.indexOf("Actions.autoRescue"))}
    @Test fun boundedCpuLeaseIsReleased(){val s=source("java/com/vlesscardvpn/vpn/TunnelService.kt").substringAfter("private suspend fun <T> recoveryCpuLease").substringBefore("private fun watchdog()");assertTrue(s.contains("acquire(120_000L)"));assertTrue(s.contains("finally"));assertTrue(s.contains("wake.release()"));assertTrue(source("AndroidManifest.xml").contains("android.permission.WAKE_LOCK"))}
}
