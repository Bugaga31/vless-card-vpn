package com.vlesscardvpn.xray

import com.vlesscardvpn.core.AutoRoutePolicy
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaskSafetyR163Test {
    private val tls = Server("tls", "vless", "node.example.test", 443, "fixture-uuid", security="tls", sni="required.example.test", pcs="fixture-pin")
    private val wg = Server("wg", "wireguard", "192.0.2.1", 51820, "fixture-private", pbk="fixture-public")
    private fun tlsConfig(server: Server, mask: Mask) = XrayConfigBuilder.outbound(server,"test",mask).getJSONObject("streamSettings").getJSONObject("tlsSettings")
    private fun custom(o: JSONObject) = MyMasks.fromJson(o)

    @Test fun gentleProfileIsBoundedAndDoesNotRewriteServerIdentity() {
        val m = Masks.byId("chrome.hello.fast")!!
        assertEquals("100-200",m.length);assertEquals("0-1",m.delay);assertEquals("10",m.maxSplit)
        assertTrue(m.sni.isEmpty() && m.alpn.isEmpty());assertEquals(0,Masks.cost(m))
        val o = XrayConfigBuilder.outbound(tls,"test",m)
        val peer = o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals(tls.address,peer.getString("address"));assertEquals(tls.port,peer.getInt("port"))
        assertEquals(tls.secret,peer.getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals(tls.sni,tlsConfig(tls,m).getString("serverName"));assertEquals(tls.pcs,tlsConfig(tls,m).getString("pinnedPeerCertSha256"))
    }
    @Test fun explicitServerAlpnCannotBeReplacedByCoverAlpn() {
        val s = tls.copy(alpn="h2")
        val m = Masks.byId("vc.alpn.11")!!
        assertFalse(Masks.compatible(m,s))
        assertEquals("h2",tlsConfig(s,m).getJSONArray("alpn").getString(0))
    }
    @Test fun unchangedConfiguredAlpnRemainsCompatible() {
        val s = tls.copy(alpn="http/1.1")
        assertTrue(Masks.compatible(Masks.byId("vc.alpn.11")!!,s))
    }
    @Test fun echBoundNameIsNotOverwrittenByWhiteSni() {
        val s=tls.copy(ech="fixture-ech");val m=Masks.byId("vc.sni.vk.com")!!
        assertFalse(Masks.compatible(m,s));assertEquals(s.sni,tlsConfig(s,m).getString("serverName"))
    }
    @Test fun udpNoiseCannotBypassTcpEngineCompatibility() {
        val bad=Mask("bad","bad","chrome",noise="z5",dpi=Masks.CURRENT_DPI)
        assertFalse(Masks.compatible(bad,wg))
        assertTrue(runCatching { custom(JSONObject().put("n","z5").put("dpi",Masks.CURRENT_DPI)) }.isFailure)
    }
    @Test fun unknownHoppingAndMixedPacketFamiliesAreRejected() {
        assertFalse(Masks.compatible(Mask("bad","bad","chrome",noise="z5",hop="unknown"),wg))
        assertFalse(Masks.compatible(Mask("bad","bad","chrome",packets="1-2",noise="z5"),wg))
    }
    @Test fun tlsHelloStrategyIsNotOfferedAsNoOpOnPlainProtocols() {
        val m=Masks.byId("chrome.hello.fast")!!
        assertFalse(Masks.compatible(m,tls.copy(security="",protocol="shadowsocks")))
        assertTrue(Masks.compatible(Masks.byId("chrome.p1")!!,tls.copy(security="",protocol="shadowsocks")))
    }
    @Test fun invalidPacketAndSizeRangesFailBeforeXray() {
        for (o in listOf(JSONObject().put("p","0-2").put("l","1-3").put("d","0-1"),
            JSONObject().put("p","3-1").put("l","1-3").put("d","0-1"),
            JSONObject().put("p","tlshello").put("l","0-1").put("d","0-1"),
            JSONObject().put("p","tlshello").put("l","20-1").put("d","0-1")))
            assertTrue(runCatching { custom(o) }.isFailure)
    }
    @Test fun descendingDelayAndLadderRangesAreRejected() {
        for (o in listOf(JSONObject().put("p","tlshello").put("l","1-3").put("d","5-1"),
            JSONObject().put("p","tlshello").put("ls","1-1,5-2").put("ds","0-1,1-2"),
            JSONObject().put("p","tlshello").put("ls","1-1,2-5").put("ds","0-1,2-1")))
            assertTrue(runCatching { custom(o) }.isFailure)
    }
    @Test fun zeroDelayIsValidButZeroLengthIsNot() {
        val m=custom(JSONObject().put("p","tlshello").put("l","100-200").put("d","0-0"))
        assertEquals("0-0",m.delay);assertTrue(Masks.compatible(m,tls))
    }
    @Test fun longIntentionalPausesAreNotRankedAsFree() {
        assertTrue(Masks.cost(Masks.byId("chrome.l3")!!) > Masks.cost(Masks.byId("chrome.hello.fast")!!))
        assertTrue(Masks.cost(Masks.byId("chrome.h7")!!) > 0)
        assertEquals(0,Masks.cost(Masks.byId("chrome.n")!!))
    }
    @Test fun autoKeepsTwoTrialsAndNoCredentialOrSniMaskTrials() {
        val trials=AutoRoutePolicy.maskTrials(tls,"chrome.h4",Settings())
        assertTrue(trials.size<=2);assertTrue("chrome.hello.fast" in trials)
        trials.filter { it.isNotEmpty() }.forEach { id -> val m=Masks.byId(id)!!;assertTrue(m.sni.isEmpty() && m.alpn.isEmpty());assertTrue(Masks.compatible(m,tls)) }
        assertTrue(AutoRoutePolicy.maskTrials(wg,"",Settings()).isEmpty())
    }
}
