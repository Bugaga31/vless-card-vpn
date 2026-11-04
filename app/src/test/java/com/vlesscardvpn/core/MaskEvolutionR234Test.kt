package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MaskEvolutionR234Test {
    private val server=Server("fixture","vless","node.example.test",443,"fixture",security="tls",sni="peer.example.test")
    private val parent get()=Masks.byId("chrome.hello.fast")!!
    private fun children(s:Server=server,c:Settings=Settings(),p:List<Mask> = listOf(parent))=AutoMaskEvolution.candidates(s,c,p)
    @Test fun boundedDistinctRepeatable() {val a=children();assertEquals(8,a.size);assertEquals(a,children());assertEquals(8,a.map{it.id}.distinct().size)}
    @Test fun optOutGeneratesNothing() {assertTrue(children(c=Settings(maskEvolution=false)).isEmpty())}
    @Test fun familyFilterIsRespected() {assertTrue(children(c=Settings(maskFamilies=listOf("hello"))).isEmpty());assertEquals(8,children(c=Settings(maskFamilies=listOf("auto"))).size)}
    @Test fun fingerprintFilterIsRespected() {assertTrue(children(c=Settings(maskFps=listOf("firefox"))).isEmpty())}
    @Test fun unsupportedTransportIsNotInvented() {assertTrue(children(server.copy(protocol="wireguard")).isEmpty());assertTrue(children(server.copy(security="")).isEmpty())}
    @Test fun childrenKeepServerContractAndSoftBounds() {children().forEach {m->assertTrue(Masks.compatible(m,server));assertEquals("chrome",m.fingerprint);assertEquals("tlshello",m.packets);assertEquals("0-1",m.delay);assertTrue(m.maxSplit.toInt() in 6..12);val b=m.length.split('-').map{it.toInt()};assertTrue(b[0]>=80 && b[1]<=300 && b[1]>b[0]);assertTrue(m.sni.isEmpty() && m.alpn.isEmpty() && !m.hasNoise && !m.viaByeDpi && m.mss==0 && !m.mux && !m.tfo && !m.ipv6)}}
    @Test fun childrenRoundTripStableIds() {children().forEach {m->assertTrue(m.auto);assertEquals(m,MaskLab.load(listOf(MaskLab.store(m))).single())}}
    @Test fun parentMustBeSafe() {assertTrue(children(p=listOf(parent.copy(sni="other.test"))).isEmpty());assertTrue(children(p=listOf(parent.copy(dpi="TPWS#HELLO_RECORD"))).isEmpty())}
    @Test fun evolvedWinnerBecomesNextParent() {val a=children();assertNotEquals(a.map{it.id},children(p=listOf(a.first())).map{it.id})}
    @Test fun differentHostsHaveSeparateNeighbourhoods() {assertNotEquals(children().map{it.id},children(server.copy(port=8443)).map{it.id})}
    @Test fun poolUsesSavedWinnerAsNextParent() {val winner=children().first();val settings=Settings(autoMasks=listOf(MaskLab.store(winner)));val expected=children(c=settings,p=listOf(winner)).first();val actual=AutoMaskSearchPolicy.pool(server,winner.id,settings){true};assertTrue(actual.any{it.id==expected.id})}
    @Test fun fullReferencedStoreDoesNotEvictLiveRoute() {val old=(0..8).flatMap{children(server.copy(port=443+it))}.distinctBy{it.id}.take(60);val kept=MaskLab.load(AutoMaskEvolution.retain(old.map{MaskLab.store(it)},children(server.copy(port=9000)).first(),old.map{it.id}.toSet()));assertEquals(old.map{it.id},kept.map{it.id})}
    @Test fun realTpwsStillAvailable() {val p=AutoMaskSearchPolicy.pool(server,"",Settings()){true};assertTrue(p.any{it.dpi=="TPWS#HELLO_RECORD"});assertTrue(p.indexOfFirst{it.dpi=="TPWS#HELLO_RECORD"}<12);assertTrue(p.take(2).any{it.auto})}
    @Test fun absentEngineCannotEnterPool() {assertTrue(AutoMaskSearchPolicy.pool(server,"",Settings()){false}.none{it.viaByeDpi})}
    @Test fun retainProtectsReferencedProfiles() {val a=children();val stored=a.take(3).map{MaskLab.store(it)};val kept=MaskLab.load(AutoMaskEvolution.retain(stored,a.last(),setOf(a[0].id)));assertEquals(a[0].id,kept.first().id);assertTrue(kept.any{it.id==a.last().id});assertEquals(4,kept.size)}
    @Test fun retainingSameWinnerDeduplicates() {val a=children().first();assertEquals(1,AutoMaskEvolution.retain(listOf(MaskLab.store(a)),a,setOf(a.id)).size)}
    private fun source()=listOf(File("src/main/java/com/vlesscardvpn/core/Actions.kt"),File("app/src/main/java/com/vlesscardvpn/core/Actions.kt")).first{it.exists()}.readText().substringAfter("private suspend fun quickMaskRescue").substringBefore("private suspend fun chooseCompared")
    @Test fun onlyFullyCheckedWinnerCanBeStored() {val s=source();assertTrue(s.indexOf("winner.get() ?: return null")<s.indexOf("AutoMaskEvolution.retain"));assertTrue(s.contains("autoChecks=profile"));assertTrue(s.contains("if(AutoChecks.accepts(p)) winner"));assertTrue(s.contains("MaskSamplePolicy.unchanged"));assertTrue(s.contains("Net.underlying(app)?.toString()!=network"))}
    @Test fun maskAndProofPublishedInSameUpdate() {val s=source();assertTrue(s.contains("state.copy(settings=state.settings.copy(autoMasks=stored),states="));assertTrue(s.contains("!AutoMaskEvolution.enabled(state.settings)"));assertTrue(s.contains("MaskLab.load(stored).none"));assertTrue(s.contains("finally {own.close()}"))}
}
