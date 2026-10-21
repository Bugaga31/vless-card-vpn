package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BroadCatalogR146Test {
    private fun server(i: Int,port: Int=443,host: String="127.0.0.1")=Server("route-$i","vless",host,port,"fixture-$i")
    @Test fun catalogIncludesCandidateBeyondFirstSevenEightAnd128() {
        val routes=(0..1199).map {server(it)};val scan=ForegroundDiscovery()
        val all=scan.catalog(routes,"wifi-A")
        assertEquals(1200,all.size);assertTrue(routes[999] in all)
        scan.advanceCatalog("wifi-A",384,all.size)
        assertEquals(all[384],scan.catalog(routes,"wifi-A").first())
        assertEquals(all.first(),scan.catalog(routes,"wifi-B").first())
    }
    @Test fun broadTcpFindsLiveEndpointAtIndex700AfterClosedInitialEndpoints() {
        ServerSocket(0,128,InetAddress.getLoopbackAddress()).use { live ->
            val closed=ServerSocket(0).use {it.localPort}
            val routes=(0..999).map {server(it,if(it==700)live.localPort else closed)}
            val replies=HashMap<Int,Int>()
            val summary=CatalogTcpProbe.scan(routes,budgetMs=3000,window=64) {i,ms->replies[i]=ms}
            assertTrue("the working endpoint beyond the first window was never tested",(replies[700] ?: 0)>0)
            assertEquals(1000,summary.started);assertEquals(1,summary.reachable)
            assertTrue(summary.completed>8)
        }
    }
    @Test fun duplicateEndpointUsesOneTcpSocketButKeepsAllConfigurationsForRealChecks() {
        ServerSocket(0,128,InetAddress.getLoopbackAddress()).use {live ->
            live.soTimeout=150
            val routes=(0..19).map {server(it,live.localPort)}
            val result=CatalogTcpProbe.scan(routes) {_,_->}
            assertEquals(20,result.started);assertEquals(20,result.reachable)
            live.accept().close()
            try {live.accept().close();fail("duplicate TCP connection")}
            catch (_: java.net.SocketTimeoutException) {}
        }
    }
    @Test fun slowDnsDoesNotBlockNumericHostsOrGlobalDeadline() {
        val release=CountDownLatch(1);val entered=CountDownLatch(1)
        ServerSocket(0).use {live ->
            try {
                val routes=listOf(server(0,host="slow.invalid"),server(1,live.localPort))
                val found=HashMap<Int,Int>();val started=System.nanoTime()
                CatalogTcpProbe.scan(routes,budgetMs=250,resolve={
                    entered.countDown()
                    try { release.await(2,TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                    null
                }) {i,ms->found[i]=ms}
                assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue((found[1] ?: 0)>0)
                assertTrue("DNS held the scan past its budget",(System.nanoTime()-started)/1000000<900)
            } finally {release.countDown()}
        }
    }
    @Test fun dnsFailureIsUnknownNotEvidenceThatServerFailed() {
        val results=mutableListOf<Int>()
        val summary=CatalogTcpProbe.scan(listOf(server(0,host="unknown.invalid")),resolve={null}) {_,ms->results+=ms}
        assertEquals(listOf(-1),results);assertEquals(0,summary.started);assertEquals(0,summary.reachable)
    }
    @Test fun cancellationBeforeStartDoesNotOpenPortsOrInventResults() {
        var calls=0
        val summary=CatalogTcpProbe.scan((0..999).map {server(it)},bindCancel={it()}) {_,_->calls++}
        assertEquals(0,summary.started);assertEquals(0,calls)
    }
    @Test fun noCatalogNeverInventsEightRoutes() {
        assertEquals(0,CatalogTcpProbe.scan(emptyList()) {_,_->error("no work")}.total)
        assertTrue(ForegroundDiscovery().catalog(emptyList(),"wifi").isEmpty())
    }
    @Test fun servicePairsContinueBeyondFirstFourInsteadOfFixedTwoWaves() {
        val routes=(0..29).map {server(it,host="host$it.invalid")};val seen=mutableSetOf<String>()
        repeat(5) { val wave=ForegroundDiscovery.serviceWave(routes,seen);assertEquals(2,wave.size);seen+=wave.map {it.id} }
        assertEquals(10,seen.size);assertTrue(routes[8].id in seen)
        assertEquals(8,AutoEligibility.TOP)
    }
    @Test fun coverageSeparatesCatalogTcpHttpsAndDataRatherThanShowingOnlyFinalists() {
        val report=AutoSearchReport();report.catalog(1000,950)
        repeat(300) {report.tcpStarted("tcp-$it");if(it<50)report.tcpResult("tcp-$it",10)}
        repeat(30) {report.httpsResult("https-$it",Probe(20,null,null))}
        report.httpsResult("https-0",Probe(20,true,null,httpsHosts=2,httpsChecked=true));report.flush()
        val c=report.snapshot();assertEquals(1000,c.catalog);assertEquals(950,c.eligible);assertEquals(300,c.tcp)
        assertEquals(50,c.reachable);assertEquals(30,c.https);assertEquals(1,c.data)
        assertTrue(report.summary().contains("Каталог 1000"))
    }
    @Test fun lateOldSearchCannotReplaceNewSearchCoverage() {
        val old=AutoSearchReport();old.catalog(1000,950)
        val fresh=AutoSearchReport();fresh.catalog(200,180)
        old.tcpStarted("late");old.flush()
        assertEquals(200,AutoSearchReport.coverage.value.catalog);assertEquals(0,AutoSearchReport.coverage.value.tcp)
        assertEquals(1000,old.snapshot().catalog)
    }
    @Test fun requestedSourcesMigrateOnceWithoutDuplicateOrLearningReset() {
        val before=Settings(rev=75,subscriptions=listOf("https://private.invalid/sub",Subs.DIMAN),netServers=mapOf("wifi" to listOf("learned")))
        val migrated=Settings.migrate(before)
        assertEquals(1,migrated.subscriptions.count {it==Subs.DIMAN});assertTrue(Subs.DARLENE in migrated.subscriptions)
        assertEquals(before.netServers,migrated.netServers);assertTrue("https://private.invalid/sub" in migrated.subscriptions)
        assertEquals(migrated,Settings.migrate(migrated))
        val removed=migrated.copy(subscriptions=emptyList());assertTrue(Settings.migrate(removed).subscriptions.isEmpty())
    }
    @Test fun unsupportedMieruNeverBecomesFakeVlessOrLeaksRawCredentials() {
        val raw="mieru://synthetic-private-fixture"
        assertTrue(LinkParser.parseMany(raw).isEmpty());assertEquals("unsupported-mieru",SubscriptionImport.failure(raw))
        val encoded=java.util.Base64.getEncoder().encodeToString(raw.toByteArray())
        assertEquals("unsupported-mieru",SubscriptionImport.failure(encoded))
        val label=SubscriptionImport.label(SubscriptionImport.failure(raw));assertFalse(label.contains("synthetic"));assertFalse(label.contains("://"))
        assertEquals("unsupported-or-invalid",SubscriptionImport.failure("<html>invalid</html>"))
    }
    @Test fun subscriptionDiagnosticRoundTripPreservesQuotaAndLastGoodTime() {
        val info=SubInfo(10,200,300,"fixture",400,"unsupported-mieru",500)
        assertEquals(info,SubInfo.fromJson(info.toJson()))
        assertEquals("",SubInfo.fromJson(org.json.JSONObject().put("at",400)).lastError)
        assertEquals(400L,info.at);assertEquals(500L,info.attemptedAt)
        assertFalse(Subs.DARLENE.contains('#'));assertEquals(1,Subs.CATALOG.count {it.url==Subs.DIMAN})
    }
}
