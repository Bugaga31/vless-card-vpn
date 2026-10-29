package com.vlesscardvpn
import com.vlesscardvpn.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class SearchPacingR200Test {
 private fun plan(i:PowerPolicy.Input,o:PowerPolicy.Options=PowerPolicy.Options())=PowerPolicy.evaluate(i,o)
 private fun five(p:PowerPolicy.Plan){assertEquals(5000L,SearchPacingPolicy.rest(p))}
 @Test fun normalSearchFive(){five(PowerPolicy.Plan())}
 @Test fun hotSearchFive(){five(plan(PowerPolicy.Input(1,temp=46.0)))}
 @Test fun thermalStatusSearchFive(){five(plan(PowerPolicy.Input(1,thermal=4)))}
 @Test fun callSearchFive(){five(plan(PowerPolicy.Input(1,voiceCall=true)))}
 @Test fun saverSearchFive(){five(plan(PowerPolicy.Input(1,saver=true)))}
 @Test fun ecoSearchFive(){five(plan(PowerPolicy.Input(1),PowerPolicy.Options(profile="eco")))}
 @Test fun lowBatterySearchFive(){five(plan(PowerPolicy.Input(1,battery=4)))}
 @Test fun darkSearchFive(){five(plan(PowerPolicy.Input(1,screen=false)))}
 @Test fun lowMemorySearchFive(){five(plan(PowerPolicy.Input(1,lowMemory=true)))}
 @Test fun hotStillLimitsParallelAndDefersOptionalWork(){val p=plan(PowerPolicy.Input(1,temp=46.0));five(p);assertEquals(2,p.foregroundRealParallel);assertFalse(p.allowLight);assertTrue(p.memory.hot)}
 @Test fun saverStillLimitsParallel(){val p=plan(PowerPolicy.Input(1,saver=true));five(p);assertEquals(4,p.foregroundRealParallel);assertFalse(p.allowLight)}
 @Test fun callStillLimitsOne(){val p=plan(PowerPolicy.Input(1,voiceCall=true));five(p);assertEquals(1,p.foregroundRealParallel);assertFalse(p.allowLight)}
 @Test fun coolingHysteresisDoesNotRestoreThirtySeconds(){val p=PowerPolicy.evaluate(PowerPolicy.Input(1,temp=35.0),PowerPolicy.Options(),PowerPolicy.Memory(hot=true));five(p);assertTrue(p.memory.hot)}
 @Test fun campaignUsesActualHotPolicyFive()=runBlocking {val p=plan(PowerPolicy.Input(1,temp=46.0));val waits=mutableListOf<Long>();var n=0;AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},restMs={SearchPacingPolicy.rest(p)},pause={waits+=it}) {_,_->if(++n==3) "ok" else null};assertEquals(listOf(5000L,5000L),waits)}
 @Test fun reportedWaitMatchesFive()=runBlocking {val p=plan(PowerPolicy.Input(1,saver=true));val seen=mutableListOf<AutoSearchCampaign.WaitState>();var n=0;AutoSearchCampaign.runPersistent({"cell"},{true},{0L},{_,_->},restMs={SearchPacingPolicy.rest(p)},pause={},onWait={seen+=it}) {_,_->if(++n==2) "ok" else null};assertEquals(5000L,seen.single().delayMs);val text=SearchFeedback.message(seen.single(),emptyMap());assertTrue(text.contains("через 5 с"));assertFalse(text.contains("30 с"))}
 @Test fun subscriptionFairnessUnchanged(){val a=(1..30).map {com.vlesscardvpn.model.Server("a$it","vless","a$it.test",443,"x",source="a")};val b=(1..30).map {com.vlesscardvpn.model.Server("b$it","vless","b$it.test",443,"x",source="b")};assertEquals(listOf("a","a","b","b"),SubscriptionQueue.window(a+b,listOf("a","b"),8).take(4).map {it.source})}
}
