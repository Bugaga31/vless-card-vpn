package com.vlesscardvpn
import com.vlesscardvpn.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class SearchPacingR200Test {
 private fun plan(i:PowerPolicy.Input,o:PowerPolicy.Options=PowerPolicy.Options())=PowerPolicy.evaluate(i,o)
 private fun one(p:PowerPolicy.Plan){assertEquals(1000L,SearchPacingPolicy.rest(p))}
 @Test fun normalSearchOne(){one(PowerPolicy.Plan())}
 @Test fun hotSearchOne(){one(plan(PowerPolicy.Input(1,temp=46.0)))}
 @Test fun thermalStatusSearchOne(){one(plan(PowerPolicy.Input(1,thermal=4)))}
 @Test fun callSearchOne(){one(plan(PowerPolicy.Input(1,voiceCall=true)))}
 @Test fun saverSearchOne(){one(plan(PowerPolicy.Input(1,saver=true)))}
 @Test fun ecoSearchOne(){one(plan(PowerPolicy.Input(1),PowerPolicy.Options(profile="eco")))}
 @Test fun lowBatterySearchOne(){one(plan(PowerPolicy.Input(1,battery=4)))}
 @Test fun darkSearchOne(){one(plan(PowerPolicy.Input(1,screen=false)))}
 @Test fun lowMemorySearchOne(){one(plan(PowerPolicy.Input(1,lowMemory=true)))}
 @Test fun hotStillLimitsParallelAndDefersOptionalWork(){val p=plan(PowerPolicy.Input(1,temp=46.0));one(p);assertEquals(2,p.foregroundRealParallel);assertFalse(p.allowLight);assertTrue(p.memory.hot)}
 @Test fun saverStillLimitsParallel(){val p=plan(PowerPolicy.Input(1,saver=true));one(p);assertEquals(4,p.foregroundRealParallel);assertFalse(p.allowLight)}
 @Test fun callStillLimitsOne(){val p=plan(PowerPolicy.Input(1,voiceCall=true));one(p);assertEquals(1,p.foregroundRealParallel);assertFalse(p.allowLight)}
 @Test fun coolingHysteresisDoesNotRestoreThirtySeconds(){val p=PowerPolicy.evaluate(PowerPolicy.Input(1,temp=35.0),PowerPolicy.Options(),PowerPolicy.Memory(hot=true));one(p);assertTrue(p.memory.hot)}
 @Test fun campaignUsesActualHotPolicyOne()=runBlocking {val p=plan(PowerPolicy.Input(1,temp=46.0));val waits=mutableListOf<Long>();var n=0;AutoSearchCampaign.runPersistent({"wifi"},{true},{0L},{_,_->},restMs={SearchPacingPolicy.rest(p)},pause={waits+=it}) {_,_->if(++n==3) "ok" else null};assertEquals(listOf(1000L,1000L),waits)}
 @Test fun reportedWaitMatchesOne()=runBlocking {val p=plan(PowerPolicy.Input(1,saver=true));val seen=mutableListOf<AutoSearchCampaign.WaitState>();var n=0;AutoSearchCampaign.runPersistent({"cell"},{true},{0L},{_,_->},restMs={SearchPacingPolicy.rest(p)},pause={},onWait={seen+=it}) {_,_->if(++n==2) "ok" else null};assertEquals(1000L,seen.single().delayMs);val text=SearchFeedback.message(seen.single(),emptyMap());assertTrue(text.contains("через 1 с"));assertFalse(text.contains("30 с"))}
 @Test fun subscriptionFairnessUnchanged(){val a=(1..30).map {com.vlesscardvpn.model.Server("a$it","vless","a$it.test",443,"x",source="a")};val b=(1..30).map {com.vlesscardvpn.model.Server("b$it","vless","b$it.test",443,"x",source="b")};assertEquals(listOf("a","a","b","b"),SubscriptionQueue.window(a+b,listOf("a","b"),8).take(4).map {it.source})}
}
