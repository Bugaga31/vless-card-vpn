package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class RuGeoRuntimeR204Test {
 private val on=Settings(ruAllDirect=true)
 private val server=Server("fixture","vless","192.0.2.1",443,"00000000-0000-4000-8000-000000000001")
 private fun config(s:Settings=on)=JSONObject(XrayConfigBuilder.vpnConfig(listOf(server to null),s,null))
 private fun rules(s:Settings=on)=config(s).getJSONObject("routing").getJSONArray("rules").let {a->(0 until a.length()).map{a.getJSONObject(it)}}
 private fun ipIndex(r:List<JSONObject>)=r.indexOfFirst {it.optString("outboundTag")=="direct"&&it.optJSONArray("ip")?.length()==12987}
 @Test fun broadModeDefaultsOff(){assertFalse(Settings().ruAllDirect);assertFalse(Settings.fromJson(JSONObject()).ruAllDirect)}
 @Test fun explicitOptInPersists(){assertTrue(Settings.fromJson(on.toJson()).ruAllDirect)}
 @Test fun explicitOptOutPersists(){assertFalse(Settings.fromJson(on.copy(ruAllDirect=false).toJson()).ruAllDirect)}
 @Test fun normalizationDoesNotOverrideConsent(){assertTrue(ServerOnlyPolicy.normalize(on).ruAllDirect);assertFalse(ServerOnlyPolicy.normalize(Settings()).ruAllDirect)}
 @Test fun sourcePinned(){assertEquals("2eddd3aa684b50372fe5996378d49158ec1d5eb6",KaringRuData.REVISION);assertEquals(64,KaringRuData.IPS_SHA256.length)}
 @Test fun domainDataDeduplicated(){assertEquals(236,KaringRuData.exact.size);assertEquals(921,KaringRuData.suffix.size);assertEquals(236,KaringRuData.exact.distinct().size)}
 @Test fun completePinnedIpDataRetained(){assertEquals(12987,KaringRuData.ipv4.size);assertEquals(12170,KaringRuData.ipv6.size)}
 @Test fun ipFamiliesRemainSeparate(){assertTrue(KaringRuData.ipv4.none{':' in it});assertTrue(KaringRuData.ipv6.all{':' in it});assertTrue(KaringRuData.ipv4.all{'/' in it})}
 @Test fun noUniversalIpBypass(){assertFalse("0.0.0.0/0" in RuGeoPolicy.ips(on));assertFalse("::/0" in RuGeoPolicy.ips(on))}
 @Test fun broadTldsAreExplicitOptIn(){assertTrue("domain:ru" in RuGeoPolicy.domains(on));assertTrue(RuGeoPolicy.domains(Settings()).isEmpty());assertTrue(RuGeoPolicy.domains(on.copy(torRouting=true,services=listOf("youtube"))).isEmpty())}
 @Test fun exactSharedCdnHostIsNotWildcard(){val d=RuGeoPolicy.domains(on);assertTrue("full:kp-config-smart-tv.akamaized.net" in d);assertFalse("domain:akamaized.net" in d)}
 @Test fun offHasNoIpRules(){assertTrue(RuGeoPolicy.ips(Settings()).isEmpty());assertEquals(-1,ipIndex(rules(Settings())))}
 @Test fun torAllDisablesBothLists(){val s=on.copy(torAll=true);assertTrue(RuWhitelistPolicy.rules(s).isEmpty());assertTrue(RuGeoPolicy.ips(s).isEmpty());assertEquals(-1,ipIndex(rules(s)))}
 @Test fun whitelistAndBroadModeIndependent(){assertTrue(RuGeoPolicy.domains(on.copy(ruWhitelist=false)).isNotEmpty());assertFalse(RuWhitelistPolicy.rules(Settings()).isEmpty())}
 @Test fun forceVpnTldRemovedFromDirectDns(){val d=RuGeoPolicy.domains(on.copy(alwaysVpn=listOf("private.example.ru")));assertFalse("domain:ru" in d);assertTrue("domain:su" in d)}
 @Test fun forceVpnExactRemoved(){val d=RuGeoPolicy.domains(on.copy(alwaysVpn=listOf("kp-config-smart-tv.akamaized.net")));assertFalse("full:kp-config-smart-tv.akamaized.net" in d)}
 @Test fun forceVpnNormalizedForDns(){val d=RuGeoPolicy.domains(on.copy(alwaysVpn=listOf("Private.Example.Ru.")));assertFalse("domain:ru" in d)}
 @Test fun ipRuleFollowsForceVpn(){val r=rules(on.copy(alwaysVpn=listOf("example.ru")));val forced=r.indexOfFirst{it.optString("outboundTag")=="proxy-0"&&it.optJSONArray("domain")?.toString()?.contains("domain:example.ru")==true};assertTrue(forced>=0);assertTrue(forced<ipIndex(r))}
 @Test fun ipOnDemandOnlyWhenOptedIn(){assertEquals("IPOnDemand",config().getJSONObject("routing").getString("domainStrategy"));assertEquals("AsIs",config(Settings()).getJSONObject("routing").getString("domainStrategy"))}
 @Test fun ipv6BlockStillPrecedesGeoIp(){val r=rules();val block=r.indexOfFirst{it.optString("outboundTag")=="block"&&it.optJSONArray("ip")?.let {a -> (0 until a.length()).any {n -> a.optString(n)=="::/0"}}==true};assertTrue(block>=0&&block<ipIndex(r))}
 @Test fun telegramIpProtectionPrecedesGeoIp(){val r=rules();val tg=r.indexOfFirst{it.optString("outboundTag")=="proxy-0"&&it.optJSONArray("ip")?.toString()?.contains("geoip:telegram")==true};assertTrue(tg>=0&&tg<ipIndex(r))}
 @Test fun quicAndStunProtectionsRetained(){val r=rules(on.copy(blockStun=true));val quic=r.indexOfFirst{it.optString("outboundTag")=="block"&&it.optString("port")=="443"&&!it.has("domain")};assertTrue(quic>=0&&quic<ipIndex(r));assertTrue(r.any{it.optString("port")=="3478,5349,19302-19309"&&it.optString("outboundTag")=="block"})}
 @Test fun unrelatedDefaultStillServer(){assertEquals("proxy-0",rules().last().getString("outboundTag"));assertEquals(12987,rules()[ipIndex(rules())].getJSONArray("ip").length())}
 @Test fun policyChangeInvalidatesChainMeasurement(){assertNotEquals(AutoChainRunner.settingsKey(Settings()),AutoChainRunner.settingsKey(on));assertNotEquals(AutoChainRunner.settingsKey(on),AutoChainRunner.settingsKey(on.copy(ruWhitelist=false)))}
}
