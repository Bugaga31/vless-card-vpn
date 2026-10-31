package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QuietWatsonR228Test {
    @Test fun successfulFullCheckNotRepeatedEveryTick() {val p=PeriodicServicePolicy();assertTrue(p.due("route",0));p.completed("route",100,true);for(t in listOf(101L,1100L,20100L,80000L))assertFalse(p.due("route",t));assertTrue(p.due("route",90100))}
    @Test fun changedNetworkMaskProfileOrCoreInvalidatesLease() {val p=PeriodicServicePolicy();p.completed("core1/net1/mask1/profile1",100,true);for(k in listOf("core2/net1/mask1/profile1","core1/net2/mask1/profile1","core1/net1/mask2/profile1","core1/net1/mask1/profile2"))assertTrue(p.due(k,101))}
    @Test fun failureAndUnknownDoNotBusyLoop() {val p=PeriodicServicePolicy();p.completed("route",100,false);assertFalse(p.due("route",3100));assertTrue(p.due("route",30100))}
    @Test fun runningWorkIsCoalescedAndClockRollbackRechecks() {val p=PeriodicServicePolicy();assertFalse(p.due("x",0,true));p.completed("x",100,true);assertTrue(p.due("x",99))}
    @Test fun watchdogNoLongerRunsEverySecond() {for(n in listOf(1000L,3000L,5000L,20000L))assertEquals(20000L,VpnRecoveryPolicy.watchPeriod(n,true));assertEquals(30000L,VpnRecoveryPolicy.watchPeriod(120000,true,true));assertEquals(120000L,VpnRecoveryPolicy.watchPeriod(120000,false))}
    @Test fun automaticTicksAreLightAndEmergencyRetryUnaffected() {assertTrue(VpnRecoveryPolicy.healthOnly(false,true));assertTrue(VpnRecoveryPolicy.healthOnly(true,true));assertEquals(1000L,VpnRecoveryPolicy.RETRY_MS)}
    @Test fun soundOnlyOncePerIncident() {val p=RecoveryAlertPolicy();assertEquals(RecoveryAlertPolicy.Alert.SOUND,p.begin(100));repeat(100){assertEquals(RecoveryAlertPolicy.Alert.NONE,p.begin(1000L+it))};assertFalse(p.recovered(false));assertTrue(p.recovered(true));assertFalse(p.recovered(true))}
    @Test fun flappingConnectionUsesQuietAlertAndNewIncidentLaterCanSound() {val p=RecoveryAlertPolicy();p.begin(100);p.recovered(true);assertEquals(RecoveryAlertPolicy.Alert.QUIET,p.begin(1000));p.recovered(true);assertEquals(RecoveryAlertPolicy.Alert.SOUND,p.begin(60100))}
    @Test fun stopDoesNotAnnounceRecovery() {val p=RecoveryAlertPolicy();p.begin(0);p.clear();assertFalse(p.recovered(true))}
    @Test fun chargingQuietOnlyDefersOptionalWork() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,battery=50,charging=true),PowerPolicy.Options());assertFalse(p.allowed);assertFalse(p.allowLight);assertFalse(p.allowReserve);assertTrue(p.reason.contains("приоритет зарядке"));for(n in listOf(1,3,5,10))assertEquals(n,ProbeConcurrencyPolicy.limit(20,n,p,true))}
    @Test fun unknownChargingBatteryDoesNotInventMeasurement() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,charging=true),PowerPolicy.Options());assertTrue(p.allowLight)}
    @Test fun almostFullChargingReleasesOnlyWhenCool() {assertTrue(PowerPolicy.evaluate(PowerPolicy.Input(0,battery=95,charging=true),PowerPolicy.Options()).allowed);assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0,battery=95,charging=true,temp=45.0),PowerPolicy.Options()).allowed)}
    @Test fun automaticCoolingOptOutRetainsCriticalThermalGuard() {assertTrue(PowerPolicy.evaluate(PowerPolicy.Input(0,battery=50,charging=true),PowerPolicy.Options(autoCool=false)).allowLight);assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0,battery=50,charging=true,thermal=4),PowerPolicy.Options(autoCool=false)).allowLight)}
    @Test fun forecastAuditScoresFutureOutcomesNotTrainingAccuracy() {val p=PhoneForecastAudit();assertNull(p.brier);p.observe(.8,true);assertEquals(.04,p.brier!!,.000001);p.observe(.2,false);assertEquals(2,p.samples);assertEquals(.04,p.brier!!,.000001)}
    @Test fun invalidPredictionsDoNotBecomeTrainingEvidence() {val p=PhoneForecastAudit();for(x in listOf(Double.NaN,Double.POSITIVE_INFINITY,-.1,1.1))p.observe(x,true);assertEquals(0,p.samples)}
    private fun source(n:String)=listOf(File("src/main/java/com/vlesscardvpn/$n"),File("app/src/main/java/com/vlesscardvpn/$n")).first{it.exists()}.readText()
    @Test fun fullCheckIsLeasedAndSuccessNoticeNeedsFullVerdict() {val s=source("vpn/TunnelService.kt");assertTrue(s.contains("serviceLease.due(serviceCheckKey()"));assertTrue(s.contains("serviceLease.completed(sampledServiceKey"));assertTrue(s.contains("recoveryAlert.recovered(liveHealthy==true)"));assertTrue(s.contains("if(settings.mode==Mode.AUTO && !asleep()) verifySoon(0)"))}
    @Test fun notificationsHonorPermissionQuietAndDnd() {val s=source("vpn/TunnelService.kt");assertTrue(s.contains("POST_NOTIFICATIONS"));assertTrue(s.contains("areNotificationsEnabled()"));assertTrue(s.contains("val audible=sound && !quiet"));assertTrue(s.contains("NotificationManager.IMPORTANCE_LOW"));assertFalse(s.contains("setBypassDnd(true)"));assertFalse(s.contains("setFullScreenIntent"))}
    @Test fun auditComesBeforeTrainingAndDoesNotControlVpn() {val s=source("core/PhoneBrain.kt");assertTrue(s.indexOf("forecastAudit.observe")<s.indexOf("model.train(it,label)"));assertTrue(s.contains("flyForecastAudit.observe"));assertFalse(s.contains("TunnelService"));assertFalse(s.contains("Store.update"))}
    @Test fun contextGuardPrecedesEveryFullProofPublication() {
        val s=java.io.File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt").let {if(it.exists())it else java.io.File("app/"+it.path)}.readText()
        val guard=s.indexOf("if(serviceCheckKey()!=sampledServiceKey) return@launch")
        assertTrue(guard>=0)
        assertTrue(guard<s.indexOf("AutoCheckEvidence.publish(profile,configuredLimit"))
        assertTrue(guard<s.indexOf("ConnectionPassport.full(httpsReachable"))
        assertTrue(guard<s.indexOf("LiveRouteEvidence.commit(old,p,profile"))
    }
    @Test fun confirmedFailureStillUsesFastRetryOutsideIdleCadence() {
        val f=java.io.File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")
        val s=(if(f.exists())f else java.io.File("app/"+f.path)).readText()
        assertTrue(s.contains("if(urgent) 3_000L"))
        assertTrue(s.contains("miss >= 2"))
    }
}
