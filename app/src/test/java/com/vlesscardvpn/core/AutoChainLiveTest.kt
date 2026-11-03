package com.vlesscardvpn.core
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import com.vlesscardvpn.core.AutoChainSelection.Verdict
import com.vlesscardvpn.core.AutoChainSelection.Purpose

class AutoChainLiveTest {
    private val a=AutoChainSelection.Sample("a",1,1000,Verdict.PASS,Verdict.PASS,Verdict.PASS,true,262144,1000,100)
    @Test fun `fresh failed current evidence is preserved for recovery`() {
        val failed=a.copy(data=Verdict.FAIL)
        assertEquals(failed,AutoChainSwitchPolicy.currentEvidence(failed,"a",1,1000))
    }
    @Test fun `current evidence cannot cross network generation`() {
        assertNull(AutoChainSwitchPolicy.currentEvidence(a,"a",2,1000))
    }
    @Test fun `stale future or mismatched current evidence is not failure proof`() {
        assertNull(AutoChainSwitchPolicy.currentEvidence(a,"a",1,61001))
        assertNull(AutoChainSwitchPolicy.currentEvidence(a,"a",1,999))
        assertNull(AutoChainSwitchPolicy.currentEvidence(a,"other",1,1000))
    }
    @Test fun `failed data route can recover to slower confirmed route`() {
        val candidate=a.copy(chainId="b",transferMs=3000,latencyMs=1000)
        Purpose.values().forEach { assertTrue(AutoChainSwitchPolicy.shouldSwitch(a.copy(data=Verdict.FAIL),candidate,it,true)) }
    }
    @Test fun `explicit service failure does not require speed gain for recovery`() {
        val candidate=a.copy(chainId="b",transferMs=3000)
        assertTrue(AutoChainSwitchPolicy.shouldSwitch(a.copy(telegram=Verdict.FAIL),candidate,Purpose.DOWNLOAD,true))
        assertTrue(AutoChainSwitchPolicy.shouldSwitch(a.copy(youtube=Verdict.FAIL),candidate,Purpose.DOWNLOAD,true))
    }
    @Test fun `failed current never permits failed or unverified replacement`() {
        val failed=a.copy(data=Verdict.FAIL)
        assertFalse(AutoChainSwitchPolicy.shouldSwitch(failed,a.copy(chainId="b",telegram=Verdict.FAIL),Purpose.DOWNLOAD,true))
        assertFalse(AutoChainSwitchPolicy.shouldSwitch(failed,a.copy(chainId="b",verifiedBody=false),Purpose.DOWNLOAD,true))
    }
    @Test fun `inconclusive service result alone retains speed hysteresis`() {
        val current=a.copy(telegram=Verdict.UNKNOWN,youtube=Verdict.UNKNOWN)
        assertFalse(AutoChainSwitchPolicy.shouldSwitch(current,current.copy(chainId="b",transferMs=1050),Purpose.DOWNLOAD,true))
    }
    @Test fun `fast generic download with slower video does not trigger switch`() {
        val current=a.copy(youtubeKbps=1000)
        val candidate=a.copy(chainId="b",transferMs=100,youtubeKbps=128)
        assertFalse(AutoChainSwitchPolicy.shouldSwitch(current,candidate,Purpose.DOWNLOAD))
        assertFalse(AutoChainSwitchPolicy.shouldSwitch(current,candidate,Purpose.BALANCED))
    }
    @Test fun `material video bottleneck improvement permits switch`() {
        val current=a.copy(youtubeKbps=128)
        val candidate=a.copy(chainId="b",youtubeKbps=1000)
        assertTrue(AutoChainSwitchPolicy.shouldSwitch(current,candidate,Purpose.DOWNLOAD))
    }
    @Test fun `unknown current trial cannot disrupt a working live route`() {
        assertFalse(AutoChainSwitchPolicy.shouldSwitch(null,a,Purpose.DOWNLOAD,currentExpected=true))
    }
    @Test fun `without an existing route a verified winner can start`() {
        assertTrue(AutoChainSwitchPolicy.shouldSwitch(null,a,Purpose.DOWNLOAD,currentExpected=false))
    }
    @Test fun `large speed improvement switches`() {assertTrue(AutoChainSwitchPolicy.shouldSwitch(a,a.copy(chainId="b",transferMs=500),Purpose.DOWNLOAD))}
    @Test fun `tiny noise does not cause flapping`() {assertFalse(AutoChainSwitchPolicy.shouldSwitch(a,a.copy(chainId="b",transferMs=950),Purpose.DOWNLOAD))}
    @Test fun `confirmed route beats faster unknown`() {assertFalse(AutoChainSwitchPolicy.shouldSwitch(a,a.copy(chainId="b",transferMs=100,telegram=Verdict.UNKNOWN),Purpose.DOWNLOAD))}
    @Test fun `confirmation can replace faster fallback`() {assertTrue(AutoChainSwitchPolicy.shouldSwitch(a.copy(telegram=Verdict.UNKNOWN),a.copy(chainId="b",transferMs=2000),Purpose.DOWNLOAD))}
    @Test fun `explicit failure cannot replace route`() {assertFalse(AutoChainSwitchPolicy.shouldSwitch(a,a.copy(chainId="b",youtube=Verdict.FAIL,transferMs=1),Purpose.DOWNLOAD))}
    @Test fun `unverified body never switches`() {assertFalse(AutoChainSwitchPolicy.shouldSwitch(null,a.copy(verifiedBody=false),Purpose.BALANCED))}
    @Test fun `same chain cannot trigger reconnect loop`() {assertFalse(AutoChainSwitchPolicy.shouldSwitch(a,a,Purpose.DOWNLOAD))}
    @Test fun `interactive prioritizes significant latency gain`() {assertTrue(AutoChainSwitchPolicy.shouldSwitch(a,a.copy(chainId="b",latencyMs=40,transferMs=2000),Purpose.INTERACTIVE))}
    @Test fun `balanced mode rejects latency regression`() {assertFalse(AutoChainSwitchPolicy.shouldSwitch(a,a.copy(chainId="b",latencyMs=300,transferMs=500),Purpose.BALANCED))}
    @Test fun `unknown purpose maps to safe balanced default`() {assertEquals(Purpose.BALANCED,AutoChainSwitchPolicy.purpose("bad"))}
    @Test fun `normal Auto enables real chain search`() {assertTrue(AutoChainLivePolicy.enabled(Settings(mode=Mode.AUTO)))}
    @Test fun `manual and explicit Tor modes not modified`() {
        listOf(Mode.SERVERS,Mode.BYEDPI,Mode.HYBRID).forEach {assertFalse(AutoChainLivePolicy.enabled(Settings(mode=it)))}
        assertFalse(AutoChainLivePolicy.enabled(Settings(mode=Mode.AUTO,torAll=true)))
    }
    @Test fun `explicit services WARP and proxy restrictions honored`() {
        listOf(Settings(mode=Mode.AUTO,services=listOf("youtube")),Settings(mode=Mode.AUTO,warpChain=true),Settings(mode=Mode.AUTO,torRouting=true),Settings(mode=Mode.AUTO,proxyOnly=true)).forEach {assertFalse(AutoChainLivePolicy.enabled(it))}
    }
    @Test fun `search can be disabled`() {assertFalse(AutoChainLivePolicy.enabled(Settings(mode=Mode.AUTO,autoChainSearch=false)))}
    @Test fun `settings round trip stores user purpose and Tor opt in`() {
        val s=Settings(mode=Mode.AUTO,autoChainSearch=true,autoChainPurpose="interactive",torAuto=true)
        val result=Settings.fromJson(s.toJson());assertEquals("interactive",result.autoChainPurpose);assertTrue(result.autoChainSearch);assertTrue(result.torAuto)
    }
    @Test fun `old settings never opt in to Tor silently`() {val s=Settings.fromJson(JSONObject());assertFalse(s.torAuto);assertTrue(s.autoChainSearch);assertEquals("balanced",s.autoChainPurpose)}
    @Test fun `invalid persisted purpose sanitized`() {assertEquals("balanced",Settings.fromJson(JSONObject().put("autoChainPurpose","bogus")).autoChainPurpose)}

    private val server=Server("fixture","socks","192.0.2.1",19051,"")
    private val settings=Settings(mode=Mode.SERVERS,torAll=true,ruDirect=false,ruDns=false,blockAds=false,blockTrackers=false,blockQuic=false)
    private fun config(s:Settings=settings)=JSONObject(XrayConfigBuilder.vpnConfig(listOf(server to null),s,null))
    private fun rules(c:JSONObject)=c.getJSONObject("routing").getJSONArray("rules").let {a -> (0 until a.length()).map {a.getJSONObject(it)}}
    @Test fun `Tor uplink must use selected server and remain loopback only`() {
        val c=config(); val ins=c.getJSONArray("inbounds")
        listOf("tor-uplink","tor-pt-uplink").forEach { tag ->
            val inbound=(0 until ins.length()).map { ins.getJSONObject(it) }.first { it.optString("tag")==tag }
            assertEquals("127.0.0.1",inbound.getString("listen"))
            assertEquals("proxy-0",rules(c).first {it.optJSONArray("inboundTag")?.toString()?.contains(tag)==true}.getString("outboundTag"))
        }
        assertFalse(c.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").optJSONObject("sockopt")?.has("dialerProxy")==true)
        assertTrue(rules(c).any {it.optString("network")=="tcp" && it.optString("outboundTag")=="tor"})
    }
    @Test fun `Tor ignores public direct exception`() {
        val c=config(settings.copy(alwaysDirect=listOf("example.test")))
        assertFalse(rules(c).any {it.optString("outboundTag")=="direct" && it.optJSONArray("domain")?.toString()?.contains("example.test")==true})
    }
    @Test fun `Tor DNS uses encrypted upstreams through Tor`() {
        val c=config();val dns=c.getJSONObject("dns").getJSONArray("servers")
        for(i in 0 until dns.length())assertTrue(dns.getJSONObject(i).getString("address").startsWith("https://"))
        assertEquals("tor",rules(c).first {it.optJSONArray("inboundTag")?.toString()?.contains("dns-module")==true}.getString("outboundTag"))
    }
    @Test fun `Tor TCP does not send UDP to Tor or direct internet`() {
        val rs=rules(config())
        assertFalse(rs.any {it.optString("network")=="udp" && it.optString("outboundTag") in setOf("tor","direct")})
        assertEquals("proxy-0",rs.last().getString("outboundTag"))
    }
    @Test fun `cannot accidentally compose Tor with reverse DPI dialer`() {
        try {XrayConfigBuilder.vpnConfig(listOf(server to Mask("fixture","Fixture","",dpi="cur")),settings,19053,serverViaTor=setOf(server.id));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun `cannot chain unknown server id`() {
        try {XrayConfigBuilder.vpnConfig(listOf(server to null),settings,null,serverViaTor=setOf("unknown"));fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun `live server Tor config exported for real Xray fixture`() {
        val dir=java.io.File("/tmp/chain-probe-configs");dir.mkdirs()
        java.io.File(dir,"LIVE_SERVER_TOR.json").writeText(config().toString(2))
    }
    @Test fun `Tor exit must be measured non RU not a model guess`() {
        val choice=AutoChainRunner.choice(null,null,AutoChainSelection.Layout.TOR,Settings())
        val sample=a.copy(chainId=choice.chain.id)
        assertFalse(AutoChainSelection.eligible(sample,choice.chain,1,1000))
        assertFalse(AutoChainSelection.eligible(sample.copy(exitCountry="RU"),choice.chain,1,1000))
        assertTrue(AutoChainSelection.eligible(sample.copy(exitCountry="DE"),choice.chain,1,1000))
    }
    @Test fun `live decision cannot cross network generation`() {
        val s=Settings(mode=Mode.AUTO);val choice=AutoChainRunner.choice(server,null,AutoChainSelection.Layout.SERVER,s)
        val m=AutoChainRunner.Measured(choice,a.copy(chainId=choice.chain.id),"network-fixture",1,AutoChainRunner.settingsKey(s))
        assertTrue(AutoChainRunner.fresh(m,s,"network-fixture",1,1000))
        assertFalse(AutoChainRunner.fresh(m,s,"other-fixture",1,1000))
        assertFalse(AutoChainRunner.fresh(m,s,"network-fixture",2,1000))
        assertFalse(AutoChainRunner.fresh(m,s,"network-fixture",1,61_001))
    }
    @Test fun `changed Tor bridge settings invalidate measured winner`() {
        val s=Settings(mode=Mode.AUTO,torAuto=true)
        val choice=AutoChainRunner.choice(null,null,AutoChainSelection.Layout.TOR,s)
        val m=AutoChainRunner.Measured(choice,a.copy(chainId=choice.chain.id,exitCountry="DE"),"network-fixture",1,AutoChainRunner.settingsKey(s))
        assertTrue(AutoChainRunner.fresh(m,s,"network-fixture",1,1000))
        assertFalse(AutoChainRunner.fresh(m,s.copy(torBridge="obfs4"),"network-fixture",1,1000))
        assertFalse(AutoChainRunner.fresh(m,s.copy(torAuto=false),"network-fixture",1,1000))
    }
    @Test fun `changing DPI strategy or native mask changes chain identity`() {
        val s=Settings();val one=AutoChainRunner.choice(server,null,AutoChainSelection.Layout.DPI_SERVER,s,"strategy1","cur")
        val two=AutoChainRunner.choice(server,null,AutoChainSelection.Layout.DPI_SERVER,s,"strategy2","cur")
        assertNotEquals(one.chain.id,two.chain.id)
        val differentMask=AutoChainRunner.choice(server,Mask("m","M","",packets="tlshello",length="1-2",delay="1"),AutoChainSelection.Layout.DPI_SERVER,s,"strategy1","cur")
        assertNotEquals(one.chain.id,differentMask.chain.id)
    }

    @Test fun `quarantined Tor winner cannot be applied from fresh cached evidence`() {
        val s=Settings(mode=Mode.AUTO,torAuto=true,torBridgeLines="cooldown-unit-unique-profile")
        val c=AutoChainRunner.choice(null,null,AutoChainSelection.Layout.TOR,s)
        val proof=AutoChainSelection.Sample(c.chain.id,1,1000,Verdict.PASS,Verdict.PASS,Verdict.PASS,true,262144,500,100,"DE")
        val m=AutoChainRunner.Measured(c,proof,"cooldown-unit-net",1,AutoChainRunner.settingsKey(s))
        assertTrue(AutoChainRunner.fresh(m,s,"cooldown-unit-net",1,1000))
        AutoChainRunner.reject(c,"cooldown-unit-net",1000,true)
        assertFalse(AutoChainRunner.fresh(m,s,"cooldown-unit-net",1,1001))
        assertFalse(AutoChainRunner.fresh(m,s,"cooldown-unit-net",1,601000)) // Expiry is not a fresh proof.
    }
}
