package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class AutoPoolRecoveryR140Test {
    private val now=500000L
    private fun server(n:String,source:String="")=Server(n,"vless","$n.invalid",443,"00000000-0000-0000-0000-000000000001",source=source)
    private fun sample(kbps:Int=10000,ms:Int=100)=ServerState(autoChecksKey=AutoChecks.key(AutoChecks.current,AutoLatencyPolicy.MAX_MS),autoChecksAt=now,realMs=ms,bigOk=true,kbps=kbps,speedNetwork="wifi",speedMask="",speedCheckedAt=now)
    @Test fun unknownTelegramRoutesRemainCandidatesNotConnectedEvidence() {
        val states=(0..7).map { sample(10000+it*1000).let { s -> if(it==0) s.copy(ytOk=true,tgOk=true,tgMs=120,serviceNetwork="wifi",serviceCheckedAt=now) else s } }
        assertEquals(listOf(states.first()),AutoEligibility.servicePool(states,{it},"wifi",now))
        assertEquals(8,states.count { it.works }) // HTTPS evidence alone is insufficient for live Auto.
        assertEquals(8,AutoEligibility.TOP)
    }
    @Test fun disabledMediaFailureDoesNotVetoCurrentProfile() {
        val good=sample().copy(tgOk=true,tgMs=120,serviceNetwork="wifi",serviceMask="",serviceCheckedAt=now); val bad=good.copy(ytOk=false)
        assertEquals(listOf(good,bad),AutoEligibility.servicePool(listOf(good,bad),{it},"wifi",now))
        assertTrue(AutoEligibility.servicePool(listOf(good,bad),{it},"cell",now).isEmpty())
        assertEquals(listOf(bad),AutoEligibility.servicePool(listOf(bad),{it},"wifi",now))
    }
    @Test fun fasterUnknownServiceCandidateBeatsSlowProvenButNeverKnownFailure() {
        val slow=sample(1000,10).copy(ytOk=true,tgOk=true,tgMs=120,serviceNetwork="wifi",serviceCheckedAt=now)
        val fast=sample(30000,700)
        assertTrue(ServerChoice.compareDownload(fast,slow,"wifi",now)<0)
        assertTrue(ServerChoice.compareDownload(slow,fast.copy(ytOk=false,serviceNetwork="wifi",serviceCheckedAt=now),"wifi",now)<0)
    }
    @Test fun speedWeightedBalancerDoesNotLetTinyPingStealAllTraffic() {
        val costs=SpeedBalance.costs(listOf(1000,30000),listOf(10,700))
        assertTrue(700*sqrt(costs[1]) < 10*sqrt(costs[0]))
        assertTrue(costs.all { it.isFinite() && it>0 })
        val strategy=SpeedBalance.strategy(listOf(1000,30000),listOf(10,700))
        assertEquals("leastLoad",strategy.getString("type"))
        assertEquals(1,strategy.getJSONObject("settings").getInt("expected"))
    }
    @Test fun eightOutboundsRemainObservedEvenIfOnlyTwoHaveTopBandwidth() {
        val routes=(0..7).map { server("DE-$it") to null }
        val speeds=listOf(30000,25000,1000,900,800,700,600,500)
        val config=JSONObject(XrayConfigBuilder.vpnConfig(routes,Settings(mode=Mode.SERVERS),null,
            downloadSpeeds=speeds,downloadLatencies=List(8){100}))
        val outs=config.getJSONArray("outbounds")
        assertEquals(8,(0 until outs.length()).count { outs.getJSONObject(it).optString("tag").startsWith("proxy-") })
        assertTrue(config.has("observatory"));assertFalse(config.has("burstObservatory"))
        val b=config.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
        assertEquals("proxy-",b.getJSONArray("selector").getString(0))
        assertEquals(1,b.getJSONObject("strategy").getJSONObject("settings").getInt("expected"))
        assertEquals(8,b.getJSONObject("strategy").getJSONObject("settings").getJSONArray("costs").length())
    }
    @Test fun staleHeavyVideoPoolCannotOverrideSpeedAwareLiveBalancer() {
        val cfg=JSONObject(XrayConfigBuilder.vpnConfig((0..7).map { server("DE-$it") to null },Settings(),null,
            heavy=setOf(0,1),downloadSpeeds=listOf(1000,900,30000,25000,800,700,600,500),downloadLatencies=List(8){100}))
        val balancers=cfg.getJSONObject("routing").getJSONArray("balancers")
        assertFalse((0 until balancers.length()).any { balancers.getJSONObject(it).getString("tag")=="heavy" })
    }
    @Test fun changedSpeedProfileRemainsPendingEvenWithTheSameSelectedIds() {
        assertEquals("",SpeedBalance.fingerprint(emptyList(),emptyList()))
        assertEquals("",SpeedBalance.fingerprint(listOf(0,0),listOf(100,200)))
        assertNotEquals(SpeedBalance.fingerprint(listOf(1000,30000),listOf(10,700)),
            SpeedBalance.fingerprint(listOf(30000,1000),listOf(10,700)))
    }
    @Test fun manualBalancingIsNotSilentlyOverridden() {
        val cfg=JSONObject(XrayConfigBuilder.vpnConfig(listOf(server("a") to null,server("b") to null),Settings(balance=Balance.ROUND_ROBIN),null))
        assertEquals("roundRobin",cfg.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getJSONObject("strategy").getString("type"))
    }
    @Test fun sourceLearningProposesOtherConfigsFromFastSourceNotJustSameHost() {
        val a=server("a","https://public.example/fast");val b=server("b","https://public.example/slow")
        val c=server("c",a.source);val d=server("d",b.source)
        val states=mapOf(a.id to sample(30000),b.id to sample(1000))
        assertEquals(listOf(c,d),SourceAffinity.order(listOf(d,c),listOf(a,b),{states[it.id]?:ServerState()},"wifi",now))
        assertTrue(SourceAffinity.order(listOf(d,c),listOf(a,b),{states[it.id]?:ServerState()},"cell",now).isEmpty())
    }
    @Test fun cloudProvenanceCanTeachSourceForAggregatedConfigsWithoutChangingSourceField() {
        val a=server("a","cloud-manifest"); val b=server("b","cloud-manifest")
        assertEquals(listOf(b),SourceAffinity.order(listOf(b),listOf(a),{sample()},"wifi",now){listOf("https://public.example/source")})
        assertEquals("cloud-manifest",b.source)
    }
    @Test fun sourceHintsNeverInventConnectivityForNewCandidate() {
        val a=server("a","https://example.org/sub");val b=server("b",a.source)
        val fresh=ServerState()
        SourceAffinity.order(listOf(b),listOf(a),{if(it.id==a.id) sample() else fresh},"wifi",now)
        assertFalse(fresh.works);assertEquals(0,fresh.kbps)
    }
    @Test fun cloudFastChallengerCannotBeDisplacedByUnmeasuredLowPingClones() {
        val leader=server("leader");val low=server("low");val cloud=server("cloud")
        val states=mapOf(leader.id to sample(1000),low.id to ServerState(realMs=1),cloud.id to ServerState(realMs=400))
        val finalists=AutoSelection.finalists(listOf(leader,low,cloud),{states.getValue(it.id)},"wifi",now){if(it.id==cloud.id) 50000 else 0}
        assertEquals(cloud,finalists[1])
    }
    @Test fun nativeXrayAcceptsActualEightOutboundSpeedAwareConfigurationWhenAvailable() {
        val binary=System.getenv("XRAY_QA") ?: return
        val config=XrayConfigBuilder.vpnConfig((0..7).map{server("DE-$it") to null},Settings(mode=Mode.SERVERS),null,
            downloadSpeeds=listOf(30000,25000,1000,900,800,700,600,500),downloadLatencies=List(8){100})
        val file=java.io.File.createTempFile("speed-balance-", ".json")
        try {
            file.writeText(config)
            val process=ProcessBuilder(binary,"run","-test","-config",file.absolutePath).redirectErrorStream(true).start()
            assertTrue("Xray config validation timed out",process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS))
            val output=process.inputStream.bufferedReader().readText()
            assertEquals(output,0,process.exitValue())
        } finally { file.delete() }
    }
}
