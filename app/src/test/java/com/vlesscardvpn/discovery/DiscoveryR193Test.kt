package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test

class DiscoveryR193Test {
 private fun node(i:Int,network:String="tcp",security:String="reality") = Server("fixture$i","vless","192.0.2.$i",443,"fixture",network=network,security=security)
 private fun budget(ms:Long=1800,fg:Boolean=true,tg:Boolean=true,big:Boolean=false,yt:Boolean=false,calls:Boolean=false,attempts:Int=1)=StrictProbeBudget.budget(ms,fg,tg,big,yt,calls,attempts)
 @Test fun strictSlotHas600msBudget() {assertEquals(600L,budget())}
 @Test fun smallerCallerBudgetNotExtended() {assertEquals(200L,budget(200))}
 @Test fun noUnboundedFastServiceProbe() {assertEquals(600L,budget(0))}
 @Test fun backgroundBudgetPreserved() {assertEquals(1800L,budget(fg=false))}
 @Test fun httpsOnlyBudgetPreserved() {assertEquals(1800L,budget(tg=false))}
 @Test fun bodyProofBudgetPreserved() {assertEquals(1800L,budget(big=true))}
 @Test fun mediaBudgetPreserved() {assertEquals(1800L,budget(yt=true))}
 @Test fun callsBudgetPreserved() {assertEquals(1800L,budget(calls=true))}
 @Test fun repeatedDiagnosticBudgetPreserved() {assertEquals(1800L,budget(attempts=2))}
 @Test fun measuredGateNotExtendedBySlack() {assertFalse(AutoLatencyPolicy.accepts(Probe(501,null,null,tgOk=true,tgMs=100)));assertFalse(AutoLatencyPolicy.accepts(Probe(100,null,null,tgOk=true,tgMs=501)))}
 @Test fun exact500msStillAccepted() {assertTrue(AutoLatencyPolicy.accepts(Probe(500,null,null,tgOk=true,tgMs=500)))}
 @Test fun unknownTelegramNeverAccepted() {assertFalse(AutoLatencyPolicy.accepts(Probe(100,null,null,tgOk=null,tgMs=0)))}
 @Test fun monochromeTransportKeepsOrder() {val list=(1..8).map {node(it)};assertEquals(list,TransportExploration.merge(list,list,8))}
 @Test fun leaderRetained() {val a=node(1);val b=node(2,"xhttp","tls");assertEquals(a,TransportExploration.merge(listOf(a),listOf(a,b),8).first())}
 @Test fun deepXhttpGetsReservedSlot() {val base=(1..1000).map {node(it)};val x=node(2001,"xhttp","tls");val pick=TransportExploration.merge(base.take(8),base+x,8);assertEquals(x,pick[1]);assertEquals(8,pick.size)}
 @Test fun twoTransportReservationsOnly() {val base=(1..8).map {node(it)};val extra=listOf(node(20,"xhttp","tls"),node(21,"ws","tls"),node(22,"grpc","tls"));val got=TransportExploration.merge(base,base+extra,8);assertEquals(listOf(base.first(),extra[0],extra[1]),got.take(3));assertFalse(extra[2] in got)}
 @Test fun zeroLimitEmpty() {assertTrue(TransportExploration.merge(listOf(node(1)),listOf(node(1)),0).isEmpty())}
 @Test fun oneSlotKeepsLeader() {val a=node(1);assertEquals(listOf(a),TransportExploration.merge(listOf(a),listOf(a,node(2,"xhttp")),1))}
 @Test fun unknownRankedEndpointNeverInjected() {val a=node(1);assertTrue(TransportExploration.merge(listOf(node(2)),listOf(a),8).isEmpty())}
 @Test fun noDuplicateIds() {val a=node(1);val b=node(2,"xhttp");assertEquals(2,TransportExploration.merge(listOf(a,a,b),listOf(a,a,b,b),8).size)}
 @Test fun representativeBeyondRankingWindow() {val a=(1..1000).map {node(it)};val b=node(2001,"xhttp");assertTrue(b in TransportExploration.representatives(a+b))}
 @Test fun separateTlsAndRealityFamilies() {val a=node(1);val b=node(2,security="tls");assertEquals(listOf(a,b),TransportExploration.representatives(listOf(a,b)))}
 @Test fun cooledTransportNotReintroduced() {val a=node(1);val b=node(2,"xhttp");assertEquals(listOf(a),ConnectPolicy.candidates(listOf(a,b),emptyList(),emptySet(),cooling={it==b}) {0})}
 @Test fun failedTelegramNotReintroduced() {val a=node(1);val b=node(2,"xhttp");assertEquals(listOf(a),ConnectPolicy.candidates(listOf(a,b),emptyList(),emptySet(),serviceRank={if(it==b)2 else 1}) {0})}
 private fun xhttp(extra:String="") = node(10,"xhttp","tls").copy(secret="00000000-0000-0000-0000-000000000001",host="cdn.example",sni="tls.example",path="/configured-path",mode="packet-up",extra=extra)
 private fun settings(s:Server) = com.vlesscardvpn.xray.XrayConfigBuilder.outbound(s,"fixture",null).getJSONObject("streamSettings")
 @Test fun xhttpPaddingPreserved() {val x=settings(xhttp("{\"xPaddingBytes\":\"100-1000\",\"xPaddingObfsMode\":true}")).getJSONObject("xhttpSettings").getJSONObject("extra");assertEquals("100-1000",x.getString("xPaddingBytes"));assertTrue(x.getBoolean("xPaddingObfsMode"))}
 @Test fun xhttpDownloadSettingsPreserved() {val x=settings(xhttp("{\"downloadSettings\":{\"address\":\"down.example\",\"port\":443,\"network\":\"xhttp\",\"security\":\"tls\"}}")).getJSONObject("xhttpSettings").getJSONObject("extra");assertEquals("down.example",x.getJSONObject("downloadSettings").getString("address"))}
 @Test fun xhttpXmuxPreserved() {val x=settings(xhttp("{\"xmux\":{\"maxConcurrency\":\"16-32\",\"hKeepAlivePeriod\":0}}")).getJSONObject("xhttpSettings").getJSONObject("extra");assertEquals("16-32",x.getJSONObject("xmux").getString("maxConcurrency"))}
 @Test fun xhttpSourcePathModeAndSniPreserved() {val x=settings(xhttp());assertEquals("tls.example",x.getJSONObject("tlsSettings").getString("serverName"));assertEquals("packet-up",x.getJSONObject("xhttpSettings").getString("mode"));assertEquals("/configured-path",x.getJSONObject("xhttpSettings").getString("path"))}
 @Test fun xhttpOuterMuxNotInjected() {val x=com.vlesscardvpn.xray.XrayConfigBuilder.outbound(xhttp(),"fixture",null,true);assertFalse(x.has("mux"))}
 @Test fun xhttpExplicitAlpnCannotBeOverridden() {val x=xhttp().copy(alpn="h3");assertFalse(com.vlesscardvpn.xray.Masks.signatureOk(com.vlesscardvpn.xray.Mask("fixture","fixture","chrome",alpn="h2,http/1.1"),x))}
}
