package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AutoRecoveryR224Test {
    @Test fun autoWatchdogDoesNotPauseWhenScreenSleeps() {assertFalse(VpnRecoveryPolicy.pauseWatchdog(true,true))}
    @Test fun manualQuietPolicyIsUnchanged() {assertTrue(VpnRecoveryPolicy.pauseWatchdog(true,false))}
    @Test fun awakeAutoDoesNotPause() {assertFalse(VpnRecoveryPolicy.pauseWatchdog(false,true))}
    @Test fun awakeManualDoesNotPause() {assertFalse(VpnRecoveryPolicy.pauseWatchdog(false,false))}
    @Test fun quietAutoUsesOnlyLightLiveness() {assertTrue(VpnRecoveryPolicy.healthOnly(true,true))}
    @Test fun awakeAutoKeepsFullSelectedChecks() {assertFalse(VpnRecoveryPolicy.healthOnly(false,true))}
    @Test fun manualDoesNotGainAutoHealthMode() {assertFalse(VpnRecoveryPolicy.healthOnly(true,false))}
    @Test fun quietCadenceIsFinite() {assertEquals(10000L,VpnRecoveryPolicy.watchPeriod(120000,true,true))}
    @Test fun awakeCadenceDoesNotRegress() {assertEquals(5000L,VpnRecoveryPolicy.watchPeriod(120000,true))}
    @Test fun fastAwakeCadenceRetained() {assertEquals(3000L,VpnRecoveryPolicy.watchPeriod(3000,true))}
    @Test fun manualCadenceUnchanged() {assertEquals(120000L,VpnRecoveryPolicy.watchPeriod(120000,false,true))}
    @Test fun recoveryRetryRemainsOneSecond() {assertEquals(1000L,VpnRecoveryPolicy.RETRY_MS)}
    private fun watchdog():String {
        val p=listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"),File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first {it.exists()}
        return p.readText().substringAfter("    private fun watchdog()").substringBefore("    private var scoutJob")
    }
    @Test fun noUnconditionalSleepGateSurvivesInWatchdog() {
        val s=watchdog();assertFalse(s.contains("if (asleep())"));assertFalse(s.contains("|| asleep()) continue"))
        assertTrue(s.contains("VpnRecoveryPolicy.pauseWatchdog"));assertTrue(s.contains("VpnRecoveryPolicy.healthOnly"))
    }
    @Test fun lightProofNeverRenewsSelectedProfileAdmission() {
        val s=watchdog();assertTrue(s.contains("if(settings.mode==Mode.AUTO && !healthOnly)"))
        val start=s.indexOf("if(profile!=null) {");val publish=s.indexOf("AutoCheckEvidence.publish")
        assertTrue(start>=0 && publish>start)
    }
    @Test fun confirmedFailureStillUsesStrictRescue() {
        val s=watchdog();assertTrue(s.contains("miss >= 2"));assertTrue(s.contains("recoverServerRoute()"))
        assertTrue(s.contains("CancellableProbe.run"));assertTrue(s.contains("bind { call.cancel() }"))
    }
}
