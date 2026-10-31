package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FoundConnectR213Test {
    private fun server(n:Int)=Server("fixture$n","vless","192.0.2.$n",443,"fixture$n")
    @Test fun ownerPreset100to3000IsDefault() {
        assertEquals(AutoLatencyRange.Range(100,3000),AutoLatencyRange.Range())
        assertEquals(100,Settings().autoLatencyMinMs);assertEquals(3000,Settings().autoLatencyMaxMs)
    }
    @Test fun legacySaved1000IsMigratedOnce() {
        val s=Settings.fromJson(JSONObject("{\"autoLatencyMinMs\":1,\"autoLatencyMaxMs\":1000}"))
        assertEquals(100,s.autoLatencyMinMs);assertEquals(3000,s.autoLatencyMaxMs)
    }
    @Test fun customRangeAfterMigrationSurvivesReload() {
        val s=Settings(autoLatencyMinMs=50,autoLatencyMaxMs=1200)
        val r=Settings.fromJson(s.toJson());assertEquals(50,r.autoLatencyMinMs);assertEquals(1200,r.autoLatencyMaxMs)
    }
    @Test fun presetSurvivesRoundTrip() {
        val r=Settings.fromJson(Settings().toJson());assertEquals(100,r.autoLatencyMinMs);assertEquals(3000,r.autoLatencyMaxMs)
    }
    @Test fun latency578And3000AreAcceptedAnd3001NotAutomatic() {
        val r=AutoLatencyRange.Range();assertTrue(r.accepts(true,578,true,578))
        assertTrue(r.accepts(true,3000,true,3000));assertFalse(r.accepts(true,3001,true,578))
    }
    @Test fun fasterThan100IsNotExcluded() {assertTrue(AutoLatencyRange.Range().accepts(true,1,true,20))}
    @Test fun presetDoesNotAdmitFailedBodyOrUnknownTelegram() {
        assertFalse(AutoLatencyRange.Range().accepts(false,578,true,578))
        assertFalse(AutoLatencyRange.Range().accepts(true,578,null,578))
    }
    @Test fun readyButtonExistsBeforeSearchAndForWithinLimitWorking() {
        assertTrue(FoundConnectPolicy.enabled(true,false,false,1))
        assertFalse(FoundConnectPolicy.enabled(true,false,false,0))
    }
    @Test fun connectedCannotBeReplacedAndTorHasSeparateBootstrapValidation() {
        assertTrue(FoundConnectPolicy.enabled(true,true,false,1));assertFalse(FoundConnectPolicy.enabled(true,false,true,1))
        assertFalse(FoundConnectPolicy.enabled(false,false,false,1))
    }
    @Test fun tested578IsInFoundShortlistEvenWithoutCurrentProfileProof() {
        val s=server(1);val state=ServerState(realMs=578,bigOk=true)
        assertEquals(listOf(s),FoundConnectPolicy.shortlist(listOf(s)){state})
        assertEquals(1,FoundConnectPolicy.count(listOf(s)){state})
    }
    @Test fun tcpOnlyAndFailedBodyDoNotCountAsWorking() {
        val s=server(1)
        assertEquals(0,FoundConnectPolicy.count(listOf(s)){ServerState(tcpMs=20)})
        assertTrue(FoundConnectPolicy.shortlist(listOf(s)){ServerState(realMs=578,bigOk=false)}.isEmpty())
    }
    @Test fun foundCountIsUniqueAndNotCappedByProbeShortlist() {
        val ss=(1..80).map(::server);assertEquals(80,FoundConnectPolicy.count(ss+ss){ServerState(realMs=578)})
        assertEquals(64,FoundConnectPolicy.shortlist(ss){ServerState(realMs=578)}.size)
    }
    @Test fun routesExcludedByAutoRemainExcluded() {
        val s=server(1).copy(name="🇷🇺 RU");assertEquals(0,FoundConnectPolicy.count(listOf(s)){ServerState(realMs=578)})
    }
    @Test fun freshProofSkipsAllRevalidation()=runBlocking {
        var probes=0;var selects=0
        assertEquals(FoundConnectSequence.Result.READY,FoundConnectSequence.run({true},{selects++;true},{probes++}))
        assertEquals(0,probes);assertEquals(1,selects)
    }
    @Test fun staleProofRevalidatesOnlyOnceThenSelects()=runBlocking {
        val events=mutableListOf<String>();var proven=false
        val r=FoundConnectSequence.run({true},{events+="select";proven},{events+="found-only-probe";proven=true})
        assertEquals(FoundConnectSequence.Result.READY,r)
        assertEquals(listOf("select","found-only-probe","select"),events)
    }
    @Test fun failedRevalidationDoesNotStartTunnelOrRetryForever()=runBlocking {
        var probes=0;var selects=0
        val r=FoundConnectSequence.run({true},{selects++;false},{probes++})
        assertEquals(FoundConnectSequence.Result.NONE,r);assertEquals(1,probes);assertEquals(2,selects)
    }
    @Test fun changedNetworkDuringProbeStopsHandoff()=runBlocking {
        var same=true;var selects=0
        val r=FoundConnectSequence.run({same},{selects++;false},{same=false})
        assertEquals(FoundConnectSequence.Result.STALE,r);assertEquals(1,selects)
    }
    @Test fun alreadyChangedContextDoesNotProbe()=runBlocking {
        val r=FoundConnectSequence.run({false},{fail("must not select");false},{fail("must not probe")})
        assertEquals(FoundConnectSequence.Result.STALE,r)
    }
    private fun quarantine(m:RouteStabilityMemory,net:String="wifi",id:String="a",mask:String="m") {
        repeat(5) {m.record(net,id,mask,LinkStabilityPolicy.Sample(false,0),1000L+it,800)}
        assertTrue(m.cooling(net,id,mask,1005))
    }
    @Test fun completeFreshServiceSuccessClearsOldQuarantine() {
        val m=RouteStabilityMemory();quarantine(m);m.confirmed("wifi","a","m",true)
        assertFalse(m.cooling("wifi","a","m",1006))
    }
    @Test fun failedOrPartialProbeDoesNotClearOldQuarantine() {
        val m=RouteStabilityMemory();quarantine(m);m.confirmed("wifi","a","m",false)
        assertTrue(m.cooling("wifi","a","m",1006))
    }
    @Test fun rehabilitationDoesNotCrossRouteOrNetwork() {
        val m=RouteStabilityMemory();quarantine(m);quarantine(m,"mobile");quarantine(m,id="b");quarantine(m,mask="other")
        m.confirmed("wifi","a","m",true)
        assertTrue(m.cooling("mobile","a","m",1006));assertTrue(m.cooling("wifi","b","m",1006));assertTrue(m.cooling("wifi","a","other",1006))
    }
}
