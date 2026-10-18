package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseQualityR141Test {
    private fun server(n:String,key:String="shared")=Server(n,"wireguard","$n.invalid",2408,key)
    @Test fun sameWireguardAccountCannotOccupyEightPoolSlots() {
        val same=(0..7).map { server("DE-$it") }
        val distinct=server("NL-new","independent")
        val tcp=Server("DE-tcp","vless","tcp.invalid",443,"shared")
        val result=AutoEligibility.independent(same+distinct+tcp)
        assertEquals(listOf(same.first(),distinct,tcp),result)
    }
    @Test fun fastestEndpointOfAccountSurvivesRanking() {
        val a=server("DE-slow"); val b=server("DE-fast")
        val states=mapOf(a.id to ServerState(realMs=10,bigOk=true,kbps=1000,speedCheckedAt=100000,speedNetwork="wifi"),
            b.id to ServerState(realMs=200,bigOk=true,kbps=30000,speedCheckedAt=100000,speedNetwork="wifi"))
        assertEquals(listOf(b),AutoEligibility.connectedPool(listOf(a,b),{states.getValue(it.id)},"wifi",100000,false))
    }
    @Test fun coverProfilesNeverChangeRealitySniOrBypassCertificateAuthentication() {
        val m=Masks.byId("vc.sni.vk.com.fast")!!
        val r=Server("DE","vless","server.invalid",443,"00000000-0000-0000-0000-000000000001",security="reality",sni="required",pbk="public")
        assertFalse(Masks.compatible(m,r))
        val t=r.copy(security="tls",pcs="")
        assertFalse(Masks.compatible(m,t))
        val pinned=t.copy(pcs="ab".repeat(32))
        assertTrue(Masks.compatible(m,pinned))
        val o=XrayConfigBuilder.outbound(pinned,"p",m).getJSONObject("streamSettings")
        assertEquals(pinned.pcs,o.getJSONObject("tlsSettings").getString("pinnedPeerCertSha256"))
        assertEquals("vk.com",o.getJSONObject("tlsSettings").getString("serverName"))
        assertFalse(o.getJSONObject("sockopt").has("tcpMaxSeg"))
    }
    @Test fun allFourNewCoversAreLightAndDistinct() {
        val ids=listOf("vk.com","ya.ru","yandex.ru","vkvideo.ru").map { "vc.sni.$it.fast" }
        assertEquals(4,ids.mapNotNull(Masks::byId).size)
        for (id in ids) { val m=Masks.byId(id)!!;assertEquals(0,Masks.cost(m));assertEquals("0-1",m.delay);assertEquals(0,m.mss) }
    }
    @Test fun adBlockDoesNotBlockYouTubePlaybackHosts() {
        val rules=XrayConfigBuilder.YT_ADS
        assertTrue(rules.contains("domain:doubleclick.net"))
        assertFalse(rules.any { it=="domain:youtube.com" || it.contains("googlevideo") || it.contains("ytimg") || it.contains("youtubei.googleapis") })
    }
    @Test fun efficiencyChangesOnlyOptionalOwnAppWork() {
        val s=Settings(powerProfile="fast",oceanMotion=true,mode=Mode.HYBRID,blockAds=true,stealthSocks=true)
        val out=DeviceEfficiency.apply(s)
        assertEquals("eco",out.powerProfile);assertFalse(out.oceanMotion)
        assertEquals(s,out.copy(powerProfile=s.powerProfile,oceanMotion=s.oceanMotion,
            quietScreen=s.quietScreen,wifiMaintenance=s.wifiMaintenance,protectActiveTraffic=s.protectActiveTraffic))
        assertEquals(Mode.HYBRID,out.mode);assertTrue(out.stealthSocks)
    }
}
