package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test
class RecoveryR207Test {
 private val path=NetworkHandoff.Snapshot("12","wifi","dns-a")
 private val a=Server("A","vless","a.invalid",443,"secret")
 private val b=a.copy(name="B",address="b.invalid")
 private fun get(m:WarmReserveMemory,p:NetworkHandoff.Snapshot=path,s:List<Server> =listOf(a),mask:String="m",url:String="https://check.invalid",at:Long=1000)=m.candidates(p,s,{mask},url,at)
 private fun memory()=WarmReserveMemory().apply {record(path,a,"m","https://check.invalid",1000)}
 @Test fun freshProofUsable(){assertEquals(listOf(a),get(memory()))}
 @Test fun expiresAfterMinute(){assertTrue(get(memory(),at=61001).isEmpty())}
 @Test fun exactExpiryBoundary(){assertEquals(listOf(a),get(memory(),at=61000))}
 @Test fun clockRollbackInvalidates(){assertTrue(get(memory(),at=999).isEmpty())}
 @Test fun differentHandleRejected(){assertTrue(get(memory(),p=path.copy(handle="13")).isEmpty())}
 @Test fun differentProfileRejected(){assertTrue(get(memory(),p=path.copy(profile="cell")).isEmpty())}
 @Test fun differentPathRejected(){assertTrue(get(memory(),p=path.copy(path="dns-b")).isEmpty())}
 @Test fun changedMaskRejected(){assertTrue(get(memory(),mask="other").isEmpty())}
 @Test fun changedTestUrlRejected(){assertTrue(get(memory(),url="https://other.invalid").isEmpty())}
 @Test fun removedServerRejected(){assertTrue(get(memory(),s=emptyList()).isEmpty())}
 @Test fun changedConfigDespiteSameIdRejected(){val s=a.copy(fp="other");assertEquals(a.id,s.id);assertTrue(get(memory(),s=listOf(s)).isEmpty())}
 @Test fun renamedConfigNeedsNewProof(){assertTrue(get(memory(),s=listOf(a.copy(name="new"))).isEmpty())}
 @Test fun failedProofRemoved(){val m=memory();m.remove(listOf(a.id));assertTrue(get(m).isEmpty())}
 @Test fun otherProofSurvivesFailure(){val m=memory();m.record(path,b,"m","https://check.invalid",1000);m.remove(listOf(a.id));assertEquals(listOf(b),get(m,s=listOf(a,b)))}
 @Test fun sessionDoesNotRestoreProofs(){assertTrue(get(WarmReserveMemory()).isEmpty())}
 @Test fun boundedToTwenty(){val m=WarmReserveMemory();val all=(0..20).map {a.copy(address="s$it.invalid")};all.forEach {m.record(path,it,"m","https://check.invalid",1000)};val got=get(m,s=all);assertEquals(20,got.size);assertFalse(all.first() in got)}
 @Test fun repeatedProofDoesNotGrowPool(){val m=memory();repeat(30){m.record(path,a,"m","https://check.invalid",1000)};assertEquals(1,get(m).size)}
 @Test fun refreshedProofGetsNewDeadline(){val m=memory();m.record(path,a,"m","https://check.invalid",50000);assertEquals(listOf(a),get(m,at=100000))}
 @Test fun ecoCannotDelayAutoWatchdog(){val p=PowerPolicy.evaluate(PowerPolicy.Input(0,battery=80),PowerPolicy.Options(profile="eco"));assertEquals(60000L,VpnRecoveryPolicy.watchPeriod(p.healthyMs,true));assertFalse(p.allowed);assertTrue(p.allowReserve)}
 @Test fun saverCannotDelayAutoWatchdog(){val p=PowerPolicy.evaluate(PowerPolicy.Input(0,battery=80,saver=true),PowerPolicy.Options());assertEquals(45000L,VpnRecoveryPolicy.watchPeriod(p.healthyMs,true))}
 @Test fun nonAutoCadenceUnchanged(){assertEquals(90000L,VpnRecoveryPolicy.watchPeriod(90000,false))}
 @Test fun fasterFaultChecksRetained(){assertEquals(3000L,VpnRecoveryPolicy.watchPeriod(3000,true))}
 @Test fun invalidWatchPeriodBounded(){assertEquals(1000L,VpnRecoveryPolicy.watchPeriod(-1,true))}
 @Test fun retryOneSecondIsNotThirtySeconds(){assertEquals(1000L,VpnRecoveryPolicy.RETRY_MS)}
 @Test fun blockedOptionalScoutRechecksInFiveSeconds(){assertEquals(5000L,VpnRecoveryPolicy.RESERVE_RETRY_MS)}
 @Test fun activeCallProtectsHealthyNotBrokenRoute(){assertTrue(VoiceCallPolicy.deferRepair(true,4));assertFalse(VoiceCallPolicy.deferRepair(true,5))}
 @Test fun heavyHeatSafetyStillWins(){val p=PowerPolicy.evaluate(PowerPolicy.Input(0,temp=46.0),PowerPolicy.Options());assertFalse(p.allowReserve);assertFalse(p.allowed)}
 @Test fun optionalRefillFifteenSeconds(){val k="r207-fast";assertTrue(ReserveBudget.take(k,1000,15000));assertTrue(ReserveBudget.take(k,1000,15000));assertFalse(ReserveBudget.take(k,15999,15000));assertTrue(ReserveBudget.take(k,16000,15000))}
 @Test fun defaultRefillRemainsMinute(){val k="r207-default";assertTrue(ReserveBudget.take(k,1000));assertTrue(ReserveBudget.take(k,1000));assertFalse(ReserveBudget.take(k,60999));assertTrue(ReserveBudget.take(k,61000))}
 @Test fun refillCannotBeUnbounded(){val k="r207-bound";repeat(2){assertTrue(ReserveBudget.take(k,1000,0))};assertFalse(ReserveBudget.take(k,1001,0))}
}
