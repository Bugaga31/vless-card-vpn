package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test
class SubscriptionQueueR199Test {
 private val now=10_000_000L
 private fun node(src:String,n:Int=0,host:String="$src$n.test")=Server("$src$n","vless",host,443,"key$n",source=src)
 private fun state(ok:Boolean=true,at:Long=now,net:String="wifi",latency:Double=100.0)=ServerState(netEvidence=mapOf("$net|" to RouteEvidence(checks=5,successes=if(ok)5 else 0,ema=if(ok)1.0 else 0.0,latency=latency,streak=if(ok)0 else 1,at=at)))
 private fun ranked(c:List<Server>,states:Map<String,ServerState>,net:String="wifi")=SubscriptionQueue.quality(listOf("a","b","new"),c,{states[it.id]?:ServerState()},net,now)
 @Test fun unknownSourcesStartEqual(){assertTrue(ranked(emptyList(),emptyMap()).all {it.observed==0 && it.score==.5})}
 @Test fun provenSourceBeatsUnknown(){val a=node("a");assertEquals("a",ranked(listOf(a),mapOf(a.id to state())).first().source)}
 @Test fun failingSourceFallsBehindUnknown(){val a=node("a");assertEquals("a",ranked(listOf(a),mapOf(a.id to state(false))).last().source)}
 @Test fun otherNetworkHistoryIgnored(){val a=node("a");assertEquals(0,ranked(listOf(a),mapOf(a.id to state(net="cell"))).first {it.source=="a"}.observed)}
 @Test fun oldHistoryIgnored(){val a=node("a");assertEquals(0,ranked(listOf(a),mapOf(a.id to state(at=now-SubscriptionQueue.FRESH_MS-1))).first {it.source=="a"}.observed)}
 @Test fun futureHistoryIgnored(){val a=node("a");assertEquals(0,ranked(listOf(a),mapOf(a.id to state(at=now+1))).first {it.source=="a"}.observed)}
 @Test fun duplicateIdsDoNotInflate(){val a=node("a");assertEquals(1,ranked(List(100){a},mapOf(a.id to state())).first().observed)}
 @Test fun clonedHostCredentialsDoNotInflate(){val c=(1..100).map {node("a",it,"same.test")};assertEquals(1,ranked(c,c.associate {it.id to state()}).first().observed)}
 @Test fun newerHostFailureSupersedesOldSuccess(){val a=node("a",1,"same");val b=node("a",2,"same");assertTrue(ranked(listOf(a,b),mapOf(a.id to state(at=now-1),b.id to state(false))).first {it.source=="a"}.score<.5)}
 @Test fun sampleCountBounded(){val c=(1..100).map {node("a",it)};assertEquals(64,ranked(c,c.associate {it.id to state()}).first().observed)}
 @Test fun lowerLatencyWinsEqualReliability(){val a=node("a");val b=node("b");assertEquals("a",ranked(listOf(a,b),mapOf(a.id to state(latency=80.0),b.id to state(latency=800.0))).first().source)}
 @Test fun originalStateNotMutated(){val a=node("a");val ss=state();ranked(listOf(a),mapOf(a.id to ss));assertEquals(5,ss.evidenceFor("wifi").checks)}
 @Test fun fetchHasFourSlots(){assertEquals(listOf("a","b","c","d"),SubscriptionQueue.fetchOrder(listOf("a","b","c","d","e"),0))}
 @Test fun fetchChallengerRotates(){assertEquals(listOf("a","b","c","e"),SubscriptionQueue.fetchOrder(listOf("a","b","c","d","e"),1))}
 @Test fun fetchAllSourcesEventuallyAppear(){val all=(1..20).map {"s$it"};assertEquals(all.toSet(),(0..30).flatMap {SubscriptionQueue.fetchOrder(all,it)}.toSet())}
 @Test fun cooledSourceFilteredBeforeSlots(){assertEquals(4,SubscriptionQueue.fetchOrder((1..8).map {"s$it"}.filterNot {it=="s1" || it=="s2"},0).size)}
 @Test fun fetchDeduplicatesAndHandlesNegativeRound(){assertEquals(listOf("a","b"),SubscriptionQueue.fetchOrder(listOf("a","a","b"),-5))}
 @Test fun fetchZeroLimitEmpty(){assertTrue(SubscriptionQueue.fetchOrder(listOf("a"),1,0).isEmpty())}
 @Test fun sourceRoundRobinStopsOneHugeFeed(){val a=(1..1000).map {node("a",it)};val b=(1..3).map {node("b",it)};val w=SubscriptionQueue.window(a+b,listOf("a","b"),8);assertEquals(listOf("a","a","b","b"),w.take(4).map {it.source})}
 @Test fun sourceRankControlsFirstPortion(){val c=(1..4).flatMap {listOf(node("a",it),node("b",it))};assertEquals("b",SubscriptionQueue.window(c,listOf("b","a"),4).first().source)}
 @Test fun provenLeaderIsPreserved(){val a=node("a");val b=node("b");assertEquals(a.id,SubscriptionQueue.window(listOf(b,a),listOf("b","a"),2,a.id).first().id)}
 @Test fun noEndpointsInventedAndIdsUnique(){val c=(1..10).map {node("a",it)};val w=SubscriptionQueue.window(c+c,listOf("made-up","a"),50,"missing");assertEquals(c.map {it.id}.toSet(),w.map {it.id}.toSet());assertEquals(c.size,w.size)}
 @Test fun emptyAndZeroWindowsSafe(){assertTrue(SubscriptionQueue.window(emptyList(),emptyList()).isEmpty());assertTrue(SubscriptionQueue.window(listOf(node("a")),listOf("a"),0).isEmpty())}
 @Test fun cooledPrefixCannotHideOtherSource(){val a=(1..1000).map {node("a",it)};val b=node("b");val ready=DiscoveryWindow.pool(a+b,{it.source=="a"},{1});assertEquals((a+b).map {it.id}.toSet(),ready.map {it.id}.toSet());assertTrue(SubscriptionQueue.window(ready,listOf("a","b"),8).contains(b))}
}
