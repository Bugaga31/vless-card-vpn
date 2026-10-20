package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test

class OceanSignalQualityTest {
    @Test fun repeatedCachedSampleCannotTriggerHandover() {
        val p = HandoverPolicy()
        assertFalse(p.allow("wifi", "a", "b", 1000, 1000, true, false))
        assertFalse(p.allow("wifi", "a", "b", 100000, 1000, true, false))
        assertTrue(p.allow("wifi", "a", "b", 100001, 100001, true, false))
    }
    @Test fun holdingTimeAndTwoMeasurementsAreBothRequired() {
        val p = HandoverPolicy()
        assertFalse(p.allow("wifi", "a", "b", 1000, 1000, true, false))
        assertFalse(p.allow("wifi", "a", "b", 2000, 2000, true, false))
        assertTrue(p.allow("wifi", "a", "b", 61000, 2000, true, false))
    }
    @Test fun smallGainNeverSwitchesAndFailureRecoversWithoutWaiting() {
        val p = HandoverPolicy()
        repeat(20) { assertFalse(p.allow("wifi", "a", "b", it * 10000L, it * 10000L, false, false)) }
        assertTrue(p.allow("wifi", "a", "b", 200001, 200001, false, true))
    }
    @Test fun failureCooldownAndNetworkIsolation() {
        val p = HandoverPolicy(); p.failure("wifi", "b", 1000)
        assertFalse(p.allow("wifi", "a", "b", 2000, 2000, true, false))
        assertFalse(p.allow("cell", "a", "b", 3000, 3000, true, false))
        assertTrue(p.allow("cell", "a", "b", 64000, 64000, true, false))
    }
    @Test fun expiredComparisonsCannotAccumulateAcrossLongGaps() {
        val p = HandoverPolicy()
        p.allow("wifi", "a", "b", 1000, 1000, true, false)
        assertFalse(p.allow("wifi", "a", "b", 900000, 900000, true, false))
    }
    @Test fun unknownEndpointDoesNotBecomeTrainingFailure() {
        assertNull(AutoEligibility.learningVerdict(Probe(100, null, null, tgOk = true), true))
        assertEquals(false, AutoEligibility.learningVerdict(Probe(10, null, true, tgOk = false), true))
        assertEquals(false, AutoEligibility.learningVerdict(Probe(10, null, false, tgOk = true), true))
        assertEquals(true, AutoEligibility.learningVerdict(Probe(10, null, true, tgOk = true), true))
    }
    @Test fun cancellationNeverPenalisesRoute() {
        Thread.currentThread().interrupt()
        try { assertNull(AutoEligibility.learningVerdict(Probe(0, null, null), true)) }
        finally { Thread.interrupted() }
    }
    @Test fun serviceStatesDoNotPromoteGenericHttpsAndExpireIndependently() {
        val s = ServiceHealth.Snapshot(1000, true, false)
        assertEquals(ServiceHealth.Verdict.PASS, ServiceHealth.verdict(s.youtube, s, 1001))
        assertEquals(ServiceHealth.Verdict.FAIL, ServiceHealth.verdict(s.telegram, s, 1001))
        assertEquals(ServiceHealth.Verdict.UNKNOWN, ServiceHealth.verdict(null, s, 1001))
        assertEquals(ServiceHealth.Verdict.STALE, ServiceHealth.verdict(true, s, 400000))
        assertEquals(ServiceHealth.Verdict.CHECKING, ServiceHealth.verdict(null, s.copy(checking = true), 1001))
    }
    private val server = Server("DE", "vless", "host.invalid", 443, "test", sni = "ya.ru", security = "reality")
    private val evidence = ServerState(realMs = 100, kbps = 10000, serviceCheckedAt = 1000, speedCheckedAt = 1000, okCount = 7)
    private val old = AppState(listOf(server), mapOf(server.id to evidence))
    @Test fun strategyChangesInvalidateProofWithoutErasingLearning() {
        val next = RouteContext.invalidate(old, old.copy(settings = old.settings.copy(dnsUrl = "https://dns.example/query")))
        assertEquals(0L, next.state(server).serviceCheckedAt)
        assertEquals(0L, next.state(server).speedCheckedAt)
        assertEquals(7, next.state(server).okCount)
        assertEquals(10000, next.state(server).kbps)
    }
    @Test fun serverFieldsNotIncludedInLegacyIdStillInvalidateProof() {
        val edited = server.copy(fp = "firefox")
        assertEquals(server.id, edited.id)
        assertEquals(0L, RouteContext.invalidate(old, old.copy(servers = listOf(edited))).state(edited).serviceCheckedAt)
        assertEquals(1000L, RouteContext.invalidate(old, old.copy(servers = listOf(server.copy(name = "new name")))).state(server).serviceCheckedAt)
    }
    @Test fun presentationChangesNeverEraseProofAndRussianSniIsNotExitCountry() {
        assertEquals(1000L, RouteContext.invalidate(old, old.copy(settings = old.settings.copy(oceanMotion = false))).state(server).serviceCheckedAt)
        assertTrue(AutoEligibility.allowed(server))
        assertFalse(AutoEligibility.allowed(server.copy(name = "RU")))
        assertEquals("", Countries.of("unknown"))
    }
    @Test fun deduplicationPreservesOriginsAndLegacyRoundTrip() {
        val a = server.copy(source = "https://private.example/a")
        val b = server.copy(source = "https://private.example/b")
        val merged = SourceProvenance.merge(listOf(a), listOf(b)).single()
        assertEquals(listOf(a.source,b.source), merged.provenance)
        assertEquals(merged, Server.fromJson(merged.toJson()))
        assertEquals(listOf(a.source), Server.fromJson(a.toJson()).provenance)
        val refreshed = SourceProvenance.replace(listOf(merged), a.source, emptyList(), emptySet()).single()
        assertEquals(listOf(b.source), refreshed.provenance)
        assertEquals(a, SourceProvenance.replace(listOf(a), a.source, emptyList(), setOf(a.id)).single())
    }
    @Test fun reservePoolPrefersDifferentHostsAndDoesNotInventEightMembers() {
        val routes = (0..8).map { server.copy(name="DE-$it", secret="key-$it") } + server.copy(address="another.invalid")
        val proof = evidence.copy(serviceNetwork="wifi",serviceYtOk=true,serviceTgOk=true)
        val pool = AutoEligibility.connectedPool(routes,{proof},"wifi",1001,false)
        assertEquals(8,pool.size)
        assertTrue(pool.any { it.address=="another.invalid" })
        assertEquals(3,AutoEligibility.connectedPool(routes.take(3),{proof},"wifi",1001,false).size)
    }

    @Test fun manifestCannotRollBackKnownPublicationAndLocalStateMigratesAdditively() {
        assertFalse(CloudControl.acceptsPublication(2000,1000))
        assertFalse(CloudControl.acceptsPublication(0,0))
        assertTrue(CloudControl.acceptsPublication(2000,2000))
        assertTrue(CloudControl.acceptsPublication(2000,3000))
        val encoded = Store.encode(old)
        assertEquals(2,encoded.getInt("schemaVersion"))
        assertEquals(old,Store.decode(encoded))
        encoded.remove("schemaVersion")
        assertEquals(old,Store.decode(encoded))
    }

}
