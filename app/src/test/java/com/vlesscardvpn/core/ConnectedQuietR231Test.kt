package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConnectedQuietR231Test {
    private fun source(n:String)=listOf(File("src/main/java/com/vlesscardvpn/$n"),File("app/src/main/java/com/vlesscardvpn/$n")).first{it.exists()}.readText()
    @Test fun passingRouteIsNotRetestedOnClockExpiry() {val p=PeriodicServicePolicy();p.completed("r",100,true);for(t in listOf(101L,90100L,300100L,86400100L))assertFalse(p.due("r",t))}
    @Test fun newNetworkOrMaskStillNeedsCheck() {val p=PeriodicServicePolicy();p.completed("net1/mask1",100,true);assertTrue(p.due("net2/mask1",101));assertTrue(p.due("net1/mask2",101))}
    @Test fun changedProfileStillNeedsCheck() {val p=PeriodicServicePolicy();p.completed("r/p1",100,true);assertTrue(p.contextChanged("r/p2"));assertTrue(p.due("r/p2",101))}
    @Test fun runningFullCheckIsNeverDuplicated() {val p=PeriodicServicePolicy();assertFalse(p.due("new",100,true,true))}
    @Test fun suspicionAllowsBoundedVerificationNotEverySecond() {val p=PeriodicServicePolicy();p.completed("r",100,true);assertFalse(p.due("r",1100,suspectedFailure=true));assertTrue(p.due("r",30100,suspectedFailure=true))}
    @Test fun completedHintCheckDoesNotStartAnotherTimer() {val p=PeriodicServicePolicy();p.completed("r",100,true);assertTrue(p.due("r",30100,suspectedFailure=true));p.completed("r",30200,true);assertFalse(p.due("r",30300,suspectedFailure=true));assertFalse(p.due("r",999999))}
    @Test fun failedOrUnknownStillHasBoundedEventRetry() {val p=PeriodicServicePolicy();p.completed("r",100,false);assertFalse(p.due("r",1100));assertTrue(p.due("r",30100))}
    @Test fun rollbackNeverAcceptsFutureProof() {val p=PeriodicServicePolicy();p.completed("r",100,true);assertTrue(p.due("r",99))}
    @Test fun quietSuccessDoesNotExtendAdmissionFreshness() {assertFalse(AutoChecks.fresh(AutoChecks.key(AutoCheckProfile.of(emptyList()),3000),AutoCheckProfile.of(emptyList()),3000,100,300101))}
    @Test fun watchdogHasNoAgeDrivenFullCheck() {val s=source("vpn/TunnelService.kt");assertFalse(s.contains("if(settings.mode==Mode.AUTO && !asleep()) verifySoon(0)"));assertTrue(s.contains("serviceLease.contextChanged(serviceCheckKey())"))}
    @Test fun healthyScoutNoLongerLaunchesReserveTesting() {val s=source("vpn/TunnelService.kt");val scout=s.substring(s.indexOf("private fun standbyScout()"),s.indexOf("private var lastHard"));assertFalse(scout.contains("Actions.prepareStandby"));assertTrue(scout.contains("Action.PREPARE -> Unit"));assertTrue(scout.contains("verifySoon(0,suspectedFailure=true)"))}
    @Test fun expiredEvidenceDoesNotPretendCheckIsRunning() {val s=source("ui/ConnectionOverview.kt");assertFalse(s.contains("status.copy(checkOk=null)"));assertTrue(s.contains("!current->HomePresentation.Verdict.STALE"));assertTrue(s.contains("val presentation=status"))}
}
