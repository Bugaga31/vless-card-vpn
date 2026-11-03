package com.vlesscardvpn.core

import com.vlesscardvpn.health.*
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.tor.TorFoundPolicy
import java.util.Base64
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class RequiredServicesR214Test {
    private fun observation(s:ModuleStatus)=ModuleObservation(HealthModule.TELEGRAM,s,578)
    @Test fun selectedDisabledIsUnknownNotNotRequired() {
        val p=ConnectionPresentationPolicy.requiredProbe(observation(ModuleStatus.DISABLED),1000,1001,true)
        assertEquals("UNKNOWN",p.status);assertFalse(p.label.contains("Не требуется"));assertNull(p.latencyMs)
    }
    @Test fun disabledByUserIsExplicit() {
        val p=ConnectionPresentationPolicy.requiredProbe(observation(ModuleStatus.PASS),1000,1001,false)
        assertEquals("DISABLED",p.status);assertEquals("Отключено в Авто",p.label);assertNull(p.latencyMs)
    }
    @Test fun currentPassAndFailArePreserved() {
        for(s in listOf(ModuleStatus.PASS,ModuleStatus.FAIL,ModuleStatus.UNKNOWN,ModuleStatus.SLOW))
            assertEquals(s.name,ConnectionPresentationPolicy.requiredProbe(observation(s),1000,1001,true).status)
    }
    @Test fun selectedMissingAndStaleNeverBecomePass() {
        assertEquals("UNKNOWN",ConnectionPresentationPolicy.requiredProbe(null,null,1001,true).status)
        assertEquals("STALE",ConnectionPresentationPolicy.requiredProbe(observation(ModuleStatus.PASS),1000,92000,true).status)
    }
    @Test fun selectedUnknownTelegramAndDiscordCannotAdmit() {
        val profile=AutoCheckProfile.of(setOf("telegram","discord"))
        val p=Probe(578,true,null,tgOk=true,tgMs=578,discordOk=true)
        assertTrue(profile.accepts(p,800))
        assertFalse(profile.accepts(p.copy(tgOk=null),800));assertNull(profile.verdict(p.copy(tgOk=null),800))
        assertFalse(profile.accepts(p.copy(discordOk=null),800));assertNull(profile.verdict(p.copy(discordOk=null),800))
    }
    @Test fun timeoutCancelsTaskAndExplainsWithoutPrivateException() {
        val f=FutureTask<Boolean>{true}
        val o=ProbeTasks.result(f,1,false)
        assertFalse(o.value);assertEquals("TIMEOUT",o.reason);assertTrue(f.isCancelled)
        val p=Probe(578,true,null,serviceNotes=mapOf("telegram" to "TIMEOUT"))
        assertTrue(AutoAdmissionReason.describe(p,AutoCheckProfile.of(setOf("telegram")),800).contains("время проверки истекло"))
    }
    @Test fun failedAndCancelledTasksKeepTheirReason() {
        val f=FutureTask<Boolean>{throw IllegalStateException("private endpoint")};f.run()
        assertEquals("ERROR",ProbeTasks.result(f,10,false).reason)
        assertFalse(ProbeTaskNotes.label("ERROR").contains("private endpoint"))
        val c=FutureTask<Boolean>{true};c.cancel(true)
        assertEquals("CANCELLED",ProbeTasks.result(c,10,false).reason)
    }
    @Test fun parentInterruptionPropagates() {
        val f=FutureTask<Boolean>{true}
        Thread.currentThread().interrupt()
        try {ProbeTasks.result(f,10,false);fail("interruption swallowed")}
        catch(expected:InterruptedException) {assertTrue(Thread.currentThread().isInterrupted)}
        finally {Thread.interrupted()}
    }
    @Test fun oldSharedPoolCanStarveRequiredService() {
        val pool=Executors.newFixedThreadPool(12);val started=CountDownLatch(12);val release=CountDownLatch(1)
        try {
            repeat(12){pool.submit{started.countDown();release.await()}}
            assertTrue(started.await(5,TimeUnit.SECONDS))
            val discord=pool.submit<Boolean>{true}
            assertEquals("TIMEOUT",ProbeTasks.result(discord,20,false).reason)
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun tenIsolatedCandidatesDoNotQueueDiscordBehindOtherCandidates() {
        val pools=(1..10).map{ProbeServiceWorkers()};val started=CountDownLatch(30);val release=CountDownLatch(1)
        try {
            pools.forEach{p->repeat(3){p.executor.submit{started.countDown();release.await()}}}
            assertTrue(started.await(5,TimeUnit.SECONDS))
            val required=pools.map{it.executor.submit<Boolean>{true}}
            required.forEach{assertTrue(it.get(5,TimeUnit.SECONDS))}
            pools.forEach{assertEquals(4,it.executor.maximumPoolSize);assertEquals(4,it.executor.queue.remainingCapacity())}
        } finally {release.countDown();pools.forEach{it.close()};pools.forEach{assertTrue(it.executor.awaitTermination(5,TimeUnit.SECONDS))}}
    }
    private val bridge="obfs4 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567 cert="+Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52))+" iat-mode=0"
    private fun tor()=Settings(torAll=true,torBridge="obfs4",torBridgeLines=bridge,autoChecks=setOf("telegram","discord"))
    @Test fun nonTorDoesNotRequireBridges() {assertNull(TorFoundPolicy.blocker(Settings()))}
    @Test fun torCannotSilentlyDisableSelectedUdp() {assertNotNull(TorFoundPolicy.blocker(tor().copy(autoChecks=setOf("voice"))))}
    @Test fun torRequiresSavedValidBridge() {
        assertNotNull(TorFoundPolicy.blocker(tor().copy(torBridgeLines="")))
        assertNotNull(TorFoundPolicy.blocker(tor().copy(torBridgeLines="invalid")))
        assertNull(TorFoundPolicy.blocker(tor()))
    }
    @Test fun changedTorSettingsInvalidatePendingHandoff() {
        val s=tor();assertNotEquals(TorFoundPolicy.key(s),TorFoundPolicy.key(s.copy(torBridgeLines=bridge.replace(":443",":8443"))))
        assertNotEquals(TorFoundPolicy.key(s),TorFoundPolicy.key(s.copy(torExitCountries="de")))
    }
}
