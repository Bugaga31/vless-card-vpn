package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PowerRoutingRuntimeR192Test {
 private val server=Server("fixture","vless","192.0.2.1",443,"fixture")
 private fun config(s:Settings=Settings(), many:Boolean=false)=JSONObject(XrayConfigBuilder.vpnConfig(
  if(many) listOf(server to null,server.copy(address="192.0.2.2") to null) else listOf(server to null),s,null))
 private fun rules(s:Settings=Settings()):List<JSONObject> = config(s).getJSONObject("routing").getJSONArray("rules").let {a->(0 until a.length()).map {a.getJSONObject(it)}}
 private fun direct(s:Settings=Settings())=rules(s).filter {it.optString("outboundTag")=="direct" && it.has("domain")}
 private fun scopedDns(s:Settings=Settings())=config(s).getJSONObject("dns").getJSONArray("servers").let {a->(0 until a.length()).mapNotNull {a.optJSONObject(it)}.filter {it.optString("address")==RuWhitelistPolicy.DNS}}
 @Test fun whitelistDefaultsOnForRequestedWorkflow() {assertTrue(Settings().ruWhitelist);assertTrue(Settings.fromJson(JSONObject()).ruWhitelist)}
 @Test fun persistedOptOutIsPreserved() {assertFalse(Settings.fromJson(Settings(ruWhitelist=false).toJson()).ruWhitelist)}
 @Test fun persistedOptInIsPreserved() {assertTrue(Settings.fromJson(Settings().toJson()).ruWhitelist)}
 @Test fun normalizationRetainsSpecificWhitelist() {assertTrue(ServerOnlyPolicy.normalize(Settings()).ruWhitelist);assertFalse(ServerOnlyPolicy.normalize(Settings(ruDirect=true)).ruDirect)}
 @Test fun whitelistNeverIncludesAllRussianIpsOrTlds() {assertTrue(RuWhitelistPolicy.rules(Settings()).none {it=="domain:ru"||it.startsWith("geoip:")||it.startsWith("geosite:")})}
 @Test fun commonServicesIncluded() {assertTrue(RuWhitelistPolicy.domains(Settings()).containsAll(listOf("gosuslugi.ru","tbank.ru","vk.com","yandex.ru")))}
 @Test fun telegramAndForeignServicesNotWhitelisted() {assertTrue(RuWhitelistPolicy.domains(Settings()).none {it in listOf("telegram.org","t.me","google.com","youtube.com","openai.com","cloudflare.com")})}
 @Test fun noGenericSharedCdnBypass() {assertTrue(RuWhitelistPolicy.domains(Settings()).none {it in listOf("cloudfront.net","akamaized.net","cloudflare.net")})}
 @Test fun optOutHasNoDomainBypass() {assertTrue(direct(Settings(ruWhitelist=false)).isEmpty());assertTrue(scopedDns(Settings(ruWhitelist=false)).isEmpty())}
 @Test fun torAllHasNoDomainBypass() {assertTrue(direct(Settings(torAll=true)).isEmpty());assertTrue(scopedDns(Settings(torAll=true)).isEmpty())}
 @Test fun forceVpnParentExcluded() {assertFalse(RuWhitelistPolicy.domains(Settings(alwaysVpn=listOf("yandex.ru"))).contains("yandex.ru"))}
 @Test fun forceVpnChildExcludesParentDns() {assertFalse(RuWhitelistPolicy.domains(Settings(alwaysVpn=listOf("passport.yandex.ru"))).contains("yandex.ru"))}
 @Test fun forceVpnCaseAndTrailingDotNormalized() {assertFalse(RuWhitelistPolicy.domains(Settings(alwaysVpn=listOf("YaNdEx.Ru."))).contains("yandex.ru"))}
 @Test fun unrelatedForceVpnKeepsWhitelist() {assertTrue(RuWhitelistPolicy.domains(Settings(alwaysVpn=listOf("example.org"))).contains("yandex.ru"))}
 @Test fun directRuleHasOnlyCuratedDomains() {assertEquals(RuWhitelistPolicy.rules(Settings()),direct().single().getJSONArray("domain").let {a->(0 until a.length()).map {a.getString(it)}})}
 @Test fun ruUdpAndTcpAreDirect() {assertEquals("tcp,udp",direct().single().getString("network"));assertTrue(config().getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing").getJSONArray("destOverride").toString().contains("quic"))}
 @Test fun ruRulePrecedesGlobalQuicBlock() {val r=rules();assertTrue(r.indexOfFirst {it.has("domain")&&it.optString("outboundTag")=="direct"}<r.indexOfFirst {it.optString("network")=="udp"&&it.optString("port")=="443"&&it.optString("outboundTag")=="block"})}
 @Test fun foreignCatchAllStillUsesServer() {assertEquals("proxy-0",rules().last().getString("outboundTag"))}
 @Test fun dnsBypassNeverGlobalFallback() {val d=scopedDns().single();assertTrue(d.getBoolean("skipFallback"));assertEquals(53,d.getInt("port"));assertEquals(RuWhitelistPolicy.rules(Settings()).size,d.getJSONArray("domains").length())}
 @Test fun unrelatedDnsStillEncryptedThroughServer() {val r=rules();val allDns=r.first {it.has("inboundTag")&&it.getJSONArray("inboundTag").toString().contains("dns-module")&&!it.has("ip")};assertEquals("proxy-0",allDns.getString("outboundTag"));val a=config().getJSONObject("dns").getJSONArray("servers");for(i in 0..2) assertTrue(a.getJSONObject(i).getString("address").startsWith("https://"))}
 @Test fun resolverBypassIsIpPortAndInboundScoped() {val r=rules().single {it.optString("outboundTag")=="direct"&&it.optString("port")=="53"};assertEquals("[\"dns-module\"]",r.getJSONArray("inboundTag").toString());assertEquals("[\"77.88.8.8/32\"]",r.getJSONArray("ip").toString())}
 @Test fun probeCadenceRespectsProfile() {assertEquals("90s",BatteryProbePolicy.interval("eco"));assertEquals("45s",BatteryProbePolicy.interval("balanced"));assertEquals("20s",BatteryProbePolicy.interval("fast"))}
 @Test fun unknownProfileUsesConservativeDefault() {assertEquals("45s",BatteryProbePolicy.interval("unknown"))}
 @Test fun nativeObservatoryNoLongerEveryTenSeconds() {val c=config(Settings(powerProfile="eco",balance=Balance.LEAST_PING),true);assertEquals("90s",c.getJSONObject("observatory").getString("probeInterval"))}
 @Test fun foregroundStillAvailableOnMeteredSaver() {val p=PowerPolicy.evaluate(PowerPolicy.Input(0,saver=true,metered=true),PowerPolicy.Options());assertFalse(p.allowed);assertEquals(8,p.foregroundRealParallel)}
 @Test fun hotForegroundRemainsBounded() {assertEquals(2,PowerPolicy.evaluate(PowerPolicy.Input(0,temp=46.0),PowerPolicy.Options()).foregroundRealParallel)}
 @Test fun callForegroundSingleSlot() {assertEquals(1,PowerPolicy.evaluate(PowerPolicy.Input(0,voiceCall=true),PowerPolicy.Options()).foregroundRealParallel)}
 private fun closed()=Channel<List<Int>>(4).apply {close()}
 @Test fun pumpAdvancesPastFirstEightWithoutSubscriptions()=runBlocking<Unit> {val tried=mutableSetOf<Int>();var winner=false;val n=ForegroundDiscoveryPump.run(closed(),{(0..99).toList()},{!winner},{it !in tried}) {b->b.take(8).forEach {tried.add(it);if(it==36)winner=true}};assertTrue(winner);assertEquals(5,n)}
 @Test fun refreshedBatchGetsImmediateSlot()=runBlocking<Unit> {val ch=Channel<List<Int>>(4);ch.trySend(listOf(99));ch.close();var found=false;ForegroundDiscoveryPump.run(ch,{listOf(1)},{!found},{true}) {assertEquals(listOf(99),it);found=true}}
 @Test fun emptyCatalogAndClosedSourceReturns()=runBlocking<Unit> {assertEquals(0,ForegroundDiscoveryPump.run(closed(),{emptyList()},{true},{true}) {fail()})}
 @Test fun duplicateSourceBatchesDoNotCountAsAttempts()=runBlocking<Unit> {val ch=Channel<List<Int>>(4);repeat(4){ch.trySend(listOf(1))};ch.close();val tried=mutableSetOf<Int>();assertEquals(1,ForegroundDiscoveryPump.run(ch,{emptyList()},{true},{it !in tried}) {tried.addAll(it)})}
 @Test fun noProgressStillBoundedToSixBatches()=runBlocking<Unit> {assertEquals(6,ForegroundDiscoveryPump.run(closed(),{listOf(1)},{true},{true}) {})}
 @Test fun networkChangeStopsBeforeNextBatch()=runBlocking<Unit> {var same=true;assertEquals(1,ForegroundDiscoveryPump.run(closed(),{listOf(1)},{same},{true}) {same=false})}
 @Test fun cancelledPumpDoesNotStartProbe()=runBlocking<Unit> {var started=false;val j=launch(start=CoroutineStart.LAZY) {ForegroundDiscoveryPump.run(closed(),{listOf(1)},{true},{true}) {started=true}};j.cancelAndJoin();assertFalse(started)}
 @Test fun outerDeadlineCancelsWaitingSource()=runBlocking<Unit> {val ch=Channel<List<Int>>();assertNull(withTimeoutOrNull(20) {ForegroundDiscoveryPump.run(ch,{emptyList()},{true},{true}) {fail()}});ch.cancel()}
 @Test fun onlyActuallyStartedIdsAreConsumed()=runBlocking<Unit> {val tried=mutableSetOf<Int>();val waves=mutableListOf<List<Int>>();ForegroundDiscoveryPump.run(closed(),{listOf(1,2,3,4)},{waves.size<2},{it !in tried}) {waves.add(it);tried.add(it.first())};assertEquals(listOf(2,3,4),waves[1]);assertEquals(setOf(1,2),tried)}
}
