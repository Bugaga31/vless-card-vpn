package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.health.*
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test
class PoolLatencyR203Test {
 private fun probe(h:Int=100,t:Int=100)=Probe(h,null,null,tgOk=true,tgMs=t)
 private fun nodes()=(1..100).map {Server("s$it","vless","host$it.test",443,"key",security="tls",source="sub${(it-1)/10}")}
 @Test fun firstPortionTwenty(){assertEquals(20,ConnectPolicy.FIRST)}
 @Test fun nextPortionTwenty(){assertEquals(20,ConnectPolicy.NEXT)}
 @Test fun shortlistDoesNotShrinkToEight(){assertEquals(20,ConnectPolicy.shortlistLimit(true))}
 @Test fun optionalPoolLimitUnchanged(){assertEquals(AutoEligibility.TOP,ConnectPolicy.shortlistLimit(false));assertEquals(8,AutoEligibility.TOP)}
 @Test fun fastStartRequestsTwenty(){assertEquals(20,ConnectPolicy.fastStart().candidates)}
 @Test fun portionRetainsEverySource(){val n=nodes();val w=SubscriptionQueue.window(n,(0..9).map{"sub$it"},ConnectPolicy.FIRST);assertEquals(20,w.size);assertEquals(10,w.map{it.source}.toSet().size);assertTrue(w.groupingBy{it.source}.eachCount().values.all{it==2})}
 @Test fun shortCatalogDoesNotInventEndpoints(){val n=nodes().take(3);assertEquals(n,SubscriptionQueue.window(n,emptyList(),20))}
 @Test fun duplicateIdsDoNotWasteSlots(){val n=nodes();val w=SubscriptionQueue.window(n+n,emptyList(),20);assertEquals(20,w.map{it.id}.distinct().size)}
 @Test fun admissionDoesNotLoseTwenty(){val n=nodes().take(20).mapIndexed{i,s->s.copy(insecure=i%2==0)};assertEquals(n.toSet(),ForegroundProbePlan.cohorts(n,true).flatten().toSet())}
 @Test fun acceptsExactThousand(){assertTrue(AutoLatencyPolicy.accepts(probe(1000,1000)))}
 @Test fun acceptsHundred(){assertTrue(AutoLatencyPolicy.accepts(probe()))}
 @Test fun acceptsFasterThanHundred(){assertTrue(AutoLatencyPolicy.accepts(probe(1,49)))}
 @Test fun acceptsFormerlySlow(){assertTrue(AutoLatencyPolicy.accepts(probe(700,900)))}
 @Test fun rejectsSlowHttps(){assertFalse(AutoLatencyPolicy.accepts(probe(1001,100)))}
 @Test fun rejectsSlowTelegram(){assertFalse(AutoLatencyPolicy.accepts(probe(100,1001)))}
 @Test fun rejectsUnknownTiming(){assertFalse(AutoLatencyPolicy.accepts(probe(100,0)))}
 @Test fun rejectsFailedService(){assertFalse(AutoLatencyPolicy.accepts(probe().copy(tgOk=false)))}
 @Test fun healthUsesSameInclusiveGate(){assertEquals(1000,ModuleHealthPolicy.MAX_LATENCY_MS);assertTrue(ModuleHealthPolicy.fastLink(true,1000,true,1000))}
 @Test fun schedulingSlackDoesNotAdmitSlow(){assertEquals(1100L,StrictProbeBudget.budget(1800,true,true,false,false,false,1));assertFalse(AutoLatencyPolicy.accepts(probe(1050,100)))}
 @Test fun callerDeadlineNeverExtended(){assertEquals(300L,StrictProbeBudget.budget(300,true,true,false,false,false,1))}
 @Test fun statisticsUseNewGate(){val s=AutoSearchStatistics();repeat(2){s.start()};s.observe(true,700,true,1000);s.observe(true,1001,true,10);assertEquals(1,s.snapshot()["ready"]);assertEquals(1,s.snapshot()["over_1000ms"]);assertFalse(s.snapshot().containsKey("over_500ms"))}
 @Test fun legacyCountersNotRelabelled(){assertEquals(0,AutoDiagnostics.compatibleSearch("{\"ordinary_probes\":{\"over_500ms\":2}}").length());assertEquals(0,AutoDiagnostics.compatibleSearch("bad").length())}
 @Test fun currentCountersPreserved(){val s=AutoSearchStatistics();s.start();s.observe(true,1000,true,1000);val e=AutoDiagnostics.searchEvent("WAVE_LIMIT",1,s.snapshot());assertEquals(e.toString(),AutoDiagnostics.compatibleSearch(e.toString()).toString())}
 @Test fun fiveSecondPauseAndPowerCapsRetained(){assertEquals(5000L,SearchPacingPolicy.rest(PowerPolicy.Plan()));for((i,cap)in listOf(PowerPolicy.Input(1) to 10,PowerPolicy.Input(1,saver=true) to 4,PowerPolicy.Input(1,temp=46.0) to 2,PowerPolicy.Input(1,voiceCall=true) to 1)){val p=PowerPolicy.evaluate(i,PowerPolicy.Options());assertEquals(cap,PowerPolicy.cap(20,p.foregroundRealParallel))}}
}
