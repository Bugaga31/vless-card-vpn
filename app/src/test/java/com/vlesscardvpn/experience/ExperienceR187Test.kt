package com.vlesscardvpn.experience

import com.vlesscardvpn.core.*
import com.vlesscardvpn.health.*
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.junit.Assert.*
import org.junit.Test

class ExperienceR187Test {
    private fun observe(m:MaskExperimentMemory,id:String,start:Long,ms:Int?,ok:Boolean?=true) {
        m.schedule("n","h","s",id,start);m.observe("n","h","s",id,start,start+1,ok,ms)
    }
    @Test fun fastestMeasuredProfileGetsOneSlotButAlternativesStillExplore() {
        val m=MaskExperimentMemory();observe(m,"slow",100,400);observe(m,"fast",200,90)
        assertEquals(listOf("fast","new"),m.choose("n","h","s",listOf("slow","new","fast"),300))
    }
    @Test fun isolatedFastOutlierDoesNotBeatSustainedLatency() {
        val m=MaskExperimentMemory();observe(m,"spike",100,400);observe(m,"spike",200,1);observe(m,"steady",300,180)
        assertEquals("steady",m.choose("n","h","s",listOf("spike","steady"),400,1).single())
    }
    @Test fun duplicateCompletionCannotInflateConfidence() {
        val m=MaskExperimentMemory();observe(m,"a",100,90)
        m.observe("n","h","s","a",100,102,true,1)
        assertEquals(1,m.counts()[1])
    }
    @Test fun expiredMeasurementsDoNotOutrankUntriedProfiles() {
        val m=MaskExperimentMemory();observe(m,"a",100,90)
        assertEquals("new",m.choose("n","h","s",listOf("a","new"),700_000,1).single())
    }
    @Test fun unknownObservationDoesNotInflateFailureOrSuccess() {
        val m=MaskExperimentMemory();observe(m,"a",100,90);observe(m,"a",200,null,null)
        assertEquals(1,m.counts()[1]);assertEquals(0,m.counts()[2]);assertEquals(1,m.counts()[3])
        assertTrue(m.choose("n","h","s",listOf("a"),201).isEmpty())
    }
    @Test fun confirmedFailureIsNotPrioritizedByOldGoodLatency() {
        val m=MaskExperimentMemory();observe(m,"a",100,90);observe(m,"a",200,null,false)
        assertEquals(listOf("new"),m.choose("n","h","s",listOf("a","new"),201))
    }
    @Test fun noMoreThanTwoVariantsAndNoDuplicates() {
        val m=MaskExperimentMemory();assertEquals(listOf("a","b"),m.choose("n","h","s",listOf("a","a","b","c"),1,99))
        assertTrue(m.choose("n","h","s",listOf("a"),1,0).isEmpty())
    }
    @Test fun observationsCannotCrossPhysicalNetworks() {
        val m=MaskExperimentMemory();observe(m,"a",100,90)
        assertEquals("new",m.choose("n","other","s",listOf("new","a"),201,1).single())
    }
    private val s=Server("fixture","vless","node.example.test",443,"00000000-0000-4000-8000-000000000001",security="tls",sni="peer.example.test",pcs="fixture-pin",vcn="peer.example.test",alpn="h2")
    @Test fun labOffersEightRealDistinctCompatibleProfiles() {
        val a=MaskWarmStart.trials(s,"",Settings(),99)
        assertEquals(8,a.size);assertEquals(8,a.map{it.id}.distinct().size)
        assertTrue(a.all {Masks.compatible(it,s)})
    }
    @Test fun ordinaryWarmStartStillOffersOnlyTwoProfiles() { assertEquals(2,MaskWarmStart.trials(s,"",Settings()).size) }
    @Test fun newProfilesNeverRewriteAuthenticationOrTlsIdentity() {
        for(id in listOf("stair","pulse","wide")) {
            val m=Masks.byId("chrome.hello.$id")!!
            assertEquals("tlshello",m.packets);assertTrue(m.maxSplit.toInt() in 1..6)
            assertTrue(m.sni.isEmpty() && m.alpn.isEmpty() && m.noise.isEmpty() && !m.mux && m.mss==0)
            val o=XrayConfigBuilder.outbound(s,"test",m)
            val peer=o.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            assertEquals(s.address,peer.getString("address"));assertEquals(s.secret,peer.getJSONArray("users").getJSONObject(0).getString("id"))
            val tls=o.getJSONObject("streamSettings").getJSONObject("tlsSettings")
            assertEquals(s.sni,tls.getString("serverName"));assertEquals(s.pcs,tls.getString("pinnedPeerCertSha256"))
            assertEquals(s.vcn,tls.getString("verifyPeerCertByName"));assertEquals(s.alpn,tls.getJSONArray("alpn").getString(0))
            assertFalse(tls.has("allowInsecure"))
        }
    }
    @Test fun newProfilesHaveFiniteHandshakeOnlyFragmentation() {
        for(id in listOf("stair","pulse")) {
            val m=Masks.byId("chrome.hello.$id")!!
            val f=XrayConfigBuilder.outbound(s,"test",m).getJSONObject("streamSettings").getJSONObject("finalmask")
                .getJSONArray("tcp").getJSONObject(0).getJSONObject("settings")
            assertEquals("tlshello",f.getString("packets"));assertEquals("6",f.getString("maxSplit"))
            assertEquals(3,f.getJSONArray("lengths").length());assertEquals(3,f.getJSONArray("delays").length())
        }
    }
    @Test fun plainAndUdpServersNeverReceiveHelloExperiments() {
        assertTrue(MaskWarmStart.trials(s.copy(security=""),"",Settings(),8).isEmpty())
        assertTrue(MaskWarmStart.trials(s.copy(protocol="wireguard"),"",Settings(),8).isEmpty())
    }
    @Test fun staleProbeCannotRemainGreen() {
        val o=ModuleObservation(HealthModule.HTTPS,ModuleStatus.PASS,40)
        assertEquals("PASS",ConnectionPresentationPolicy.probe(o,100,90_100).status)
        assertEquals("STALE",ConnectionPresentationPolicy.probe(o,100,90_101).status)
    }
    @Test fun monotonicRollbackInvalidatesPresentation() {
        assertEquals("STALE",ConnectionPresentationPolicy.probe(ModuleObservation(HealthModule.HTTPS,ModuleStatus.PASS),100,99).status)
    }
    @Test fun absentProbeIsNeverInventedSuccess() { assertEquals("UNKNOWN",ConnectionPresentationPolicy.probe(null,null,100).status) }
    @Test fun slowAndDisabledStatesRemainDistinct() {
        assertEquals("Медленно",ConnectionPresentationPolicy.probe(ModuleObservation(HealthModule.TELEGRAM,ModuleStatus.SLOW,700),100,200).label)
        val p=ConnectionPresentationPolicy.probe(ModuleObservation(HealthModule.TELEGRAM,ModuleStatus.DISABLED,700),100,200)
        assertEquals("Не требуется",p.label);assertNull(p.latencyMs)
    }
    @Test fun unknownConnectionIsNotLabelledConfirmed() {
        assertEquals("Проверяем соединение",ConnectionPresentationPolicy.heading(Tunnel.State.CONNECTED,null))
        assertEquals("Связь требует внимания",ConnectionPresentationPolicy.heading(Tunnel.State.CONNECTED,false))
    }
    @Test fun unsupportedUidCountersAreNotInventedBytes() { assertNull(TrafficAccountingPolicy.total(-1,100));assertNull(TrafficAccountingPolicy.total(100,-1)) }
    @Test fun trafficSumAndAccumulationCannotOverflow() {
        assertEquals(Long.MAX_VALUE,TrafficAccountingPolicy.total(Long.MAX_VALUE,1))
        assertEquals(Long.MAX_VALUE,TrafficAccountingPolicy.add(Long.MAX_VALUE-1,100))
    }
    @Test fun counterResetCannotSubtractMonthlyUsage() {
        assertEquals(0L,TrafficAccountingPolicy.delta(200,100));assertEquals(50L,TrafficAccountingPolicy.delta(100,150))
        assertEquals(0L,TrafficAccountingPolicy.delta(null,150));assertEquals(0L,TrafficAccountingPolicy.delta(100,null))
    }
    @Test fun negativeIncrementsAreIgnored() {assertEquals(100L,TrafficAccountingPolicy.add(100,-1))}
    @Test fun limitWarningAvoidsMultiplicationOverflow() {
        assertTrue(TrafficAccountingPolicy.nearLimit(Long.MAX_VALUE,Long.MAX_VALUE));assertFalse(TrafficAccountingPolicy.nearLimit(0,0))
        assertTrue(TrafficAccountingPolicy.nearLimit(900,1000));assertFalse(TrafficAccountingPolicy.nearLimit(899,1000))
    }
}
