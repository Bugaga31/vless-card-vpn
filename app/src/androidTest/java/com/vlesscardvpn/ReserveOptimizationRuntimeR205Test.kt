package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test
class ReserveOptimizationRuntimeR205Test {
 private val now=2000000L
 private val a=Server("a","vless","a.invalid",443,"a")
 private val b=Server("b","vless","b.invalid",443,"b")
 private fun sample(speed:Int,selected:Boolean=false)=ServerState(realMs=100,bigOk=true,kbps=speed,speedNetwork="wifi",speedMask="",speedCheckedAt=now,selected=selected)
 private fun choose(current:ServerState,candidate:ServerState,gaming:Boolean=false)=AutoPrimaryPolicy.choose(listOf(b,a),{if(it==a) current else candidate},"wifi",now,gaming).single()
 private fun plan(i:PowerPolicy.Input=PowerPolicy.Input(now,battery=80),o:PowerPolicy.Options=PowerPolicy.Options())=PowerPolicy.evaluate(i,o)
 @Test fun twentyPercentBackgroundWinnerBecomesPrimary(){val old=sample(10000,true);val next=sample(12000);assertTrue(ServerChoice.downloadUpgrade(old,next,"wifi",now));assertEquals(b,choose(old,next))}
 @Test fun twentyFourPercentIsNotSilentlyRejected(){assertEquals(b,choose(sample(10000,true),sample(12400)))}
 @Test fun nineteenPercentKeepsCurrent(){assertEquals(a,choose(sample(10000,true),sample(11999)))}
 @Test fun smallAbsoluteGainDoesNotCauseChurn(){assertEquals(a,choose(sample(1000,true),sample(1499)))}
 @Test fun fiveHundredGainBoundarySwitches(){assertEquals(b,choose(sample(2000,true),sample(2500)))}
 @Test fun wrongNetworkIsNotSpeedProof(){assertEquals(a,choose(sample(10000,true),sample(20000).copy(speedNetwork="cell")))}
 @Test fun expiredMeasurementIsNotSpeedProof(){assertEquals(a,choose(sample(10000,true),sample(20000).copy(speedCheckedAt=1)))}
 @Test fun failedCandidateCannotReplace(){assertEquals(a,choose(sample(10000,true),sample(20000).copy(bigOk=false)))}
 @Test fun unchangedLeaderDoesNotReconnect(){assertEquals(a,AutoPrimaryPolicy.choose(listOf(a,b),{sample(10000,it==a)},"wifi",now,false).single())}
 @Test fun noSelectedRouteUsesLeader(){assertEquals(b,AutoPrimaryPolicy.choose(listOf(b,a),{sample(10000)},"wifi",now,false).single())}
 @Test fun gameStillUsesLatencyNotDownload(){assertEquals(b,choose(sample(10000,true).copy(realMs=100),sample(1).copy(realMs=70),true))}
 @Test fun speedBoundaryCannotOverflow(){assertEquals(a,choose(sample(Int.MAX_VALUE-1,true),sample(Int.MAX_VALUE)))}
 @Test fun ecoAllowsTinyAutoReserveButNoHeavyDownloads(){val p=plan(o=PowerPolicy.Options(profile="eco"));assertFalse(p.allowed);assertFalse(p.allowLight);assertTrue(p.allowReserve);assertTrue(BackgroundReservePolicy.mayProbe(true,p));assertFalse(BackgroundReservePolicy.mayProbe(false,p));assertEquals(2,BackgroundReservePolicy.candidates(p))}
 @Test fun systemSaverAllowsReserve(){assertTrue(plan(PowerPolicy.Input(now,battery=80,saver=true)).allowReserve)}
 @Test fun heatStillBlocksReserve(){assertFalse(plan(PowerPolicy.Input(now,battery=80,temp=45.0)).allowReserve)}
 @Test fun voiceCallStillBlocksReserveAndSwitch(){assertFalse(plan(PowerPolicy.Input(now,battery=80,voiceCall=true)).allowReserve);assertFalse(BackgroundReservePolicy.mayApply(false,false,true))}
 @Test fun lowBatteryStillBlocksReserve(){assertFalse(plan(PowerPolicy.Input(now,battery=10)).allowReserve)}
 @Test fun manualPauseStillWins(){assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(now,battery=80),PowerPolicy.Options(),pauseUntil=now+1).allowReserve)}
 @Test fun explicitDataAndWifiRestrictionsStillWin(){assertFalse(plan(PowerPolicy.Input(now,battery=80,dataSaver=true)).allowReserve);assertFalse(plan(PowerPolicy.Input(now,battery=80,metered=true)).allowReserve)}
 @Test fun explicitQuietScreenAndChargeOnlyStillWin(){assertFalse(plan(PowerPolicy.Input(now,battery=80,screen=false)).allowReserve);assertFalse(plan(o=PowerPolicy.Options(chargeOnly=true)).allowReserve)}
 @Test fun protectedTrafficBlocksReserveAndSwitch(){assertFalse(plan(PowerPolicy.Input(now,battery=80,activeTraffic=true)).allowReserve);assertFalse(BackgroundReservePolicy.mayApply(true,true,false));assertTrue(BackgroundReservePolicy.mayApply(false,true,false))}
 @Test fun normalBackgroundPoolUnchanged(){assertEquals(ConnectPolicy.BACKGROUND,BackgroundReservePolicy.candidates(plan()))}
 @Test fun blockedOrDeferredWorkRechecksInFiveSeconds(){assertEquals(5000L,BackgroundReservePolicy.RETRY_MS)}
 @Test fun autoDoesNotBackOffToFiveMinutes(){for(calm in 0..3)assertEquals(30000L,BackgroundReservePolicy.next(true,calm));assertEquals(300000L,BackgroundReservePolicy.next(false,3))}
}
