package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SeriesDiagnosticsR222Test {
    private fun sample(ok:Boolean?=true,ms:Int=100)=LinkStabilityPolicy.Sample(ok,ms)
    @Test fun seriesDoesNotWaitForUnrelatedConnectivity() {
        ProbeServiceWorkers().use {workers->
            val first=workers.telegram.submit<LinkStabilityPolicy.Sample> {sample()}
            val measurements=AtomicInteger()
            val series=workers.telegram.submit<StabilitySeriesProbe.Result> {
                StabilitySeriesProbe.collect(first.get(1,TimeUnit.SECONDS),{1800},800) {
                    measurements.incrementAndGet();sample()
                }
            }
            // The consumer has not completed/awaited HTTPS at this point.
            assertEquals(true,series.get(1,TimeUnit.SECONDS).stable)
            assertEquals(2,measurements.get())
        }
    }
    @Test fun exhaustedBudgetNeverMeasuresOrPasses() {
        var called=false
        val r=StabilitySeriesProbe.collect(sample(),{199},800) {called=true;sample()}
        assertNull(r.stable);assertNull(r.ignoringLatency);assertEquals("TIMEOUT",r.reason);assertFalse(called)
    }
    @Test fun nextMeasurementUsesRemainingBudgetNotFreshTwoSeconds() {
        val left=ArrayDeque(listOf(1100L,240L));val allowances=mutableListOf<Long>()
        val r=StabilitySeriesProbe.collect(sample(),{left.removeFirst()},800) {allowances+=it;sample()}
        assertEquals(listOf(1100L,240L),allowances);assertEquals(true,r.stable)
    }
    @Test fun uncertainFirstCannotBecomeSuccess() {
        val r=StabilitySeriesProbe.collect(sample(null,0),{2000},800) {sample()}
        assertNull(r.stable);assertEquals("STABILITY_INCOMPLETE",r.reason)
    }
    @Test fun explicitFailureRemainsFailure() {
        val r=StabilitySeriesProbe.collect(sample(false,0),{2000},800) {sample()}
        assertEquals(false,r.stable);assertEquals(false,r.ignoringLatency);assertEquals("STABILITY_RESPONSE_FAILED",r.reason)
    }
    @Test fun spreadIsNotForgivenByLatencyConsent() {
        val r=StabilitySeriesProbe.collect(sample(ms=100),{2000},800) {sample(ms=500)}
        assertEquals(false,r.stable);assertEquals(false,r.ignoringLatency);assertEquals("STABILITY_SPREAD",r.reason)
    }
    @Test fun slowButConsistentIsOnlyLatencyFailure() {
        val r=StabilitySeriesProbe.collect(sample(ms=900),{2000},800) {sample(ms=910)}
        assertEquals(false,r.stable);assertEquals(true,r.ignoringLatency);assertEquals("STABILITY_LATENCY",r.reason)
    }
    @Test fun cancellationNeverMeasuresOrPasses() {
        var called=false;Thread.currentThread().interrupt()
        try {
            val r=StabilitySeriesProbe.collect(sample(),{2000},800) {called=true;sample()}
            assertNull(r.stable);assertEquals("CANCELLED",r.reason);assertFalse(called);assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted()}
    }
    @Test fun encryptedVideoIsAnUnsupportedTestNotDeadRoute() {
        val html="""ytInitialPlayerResponse={"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"mimeType":"video/mp4","signatureCipher":"s=encrypted&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback"}]}}"""
        assertTrue(YouTubeMediaProbe.mediaUrls(html).isEmpty())
        assertEquals("YT_SIGNATURE_UNSUPPORTED",YouTubeMediaProbe.discoveryReason(html))
    }
    @Test fun missingFormatsOrCipherDoesNotInventEncryptedSignature() {
        assertEquals("YT_NO_DIRECT_MEDIA",YouTubeMediaProbe.discoveryReason("""ytInitialPlayerResponse={"playabilityStatus":{"status":"OK"}}"""))
        assertEquals("YT_NO_DIRECT_MEDIA",YouTubeMediaProbe.discoveryReason("""ytInitialPlayerResponse={"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"mimeType":"video/mp4"}]}}"""))
    }
    @Test fun loginAndGatewayReasonsReachOverviewLabel() {
        assertTrue(ProbeTaskNotes.label("YT_LOGIN_REQUIRED").contains("YouTube требует вход"))
        assertTrue(ProbeTaskNotes.label("YT_SIGNATURE_UNSUPPORTED").contains("подписи"))
        assertTrue(ProbeTaskNotes.label("DISCORD_GATEWAY").contains("HELLO"))
    }
    @Test fun failedQualityShowsActualReasonInsteadOfNoUdp() {
        assertTrue(ServiceProbeStatus.label(true,false,"VOICE_CAUTION").contains("UDP отвечает"))
        assertTrue(ServiceProbeStatus.label(true,false,"VOICE_SLOW").contains("UDP отвечает"))
        assertTrue(ServiceProbeStatus.label(true,false,"VOICE_LOSS").contains("5%"))
        assertTrue(ServiceProbeStatus.label(true,false,"STABILITY_SPREAD").contains("150 мс"))
    }
    @Test fun disconnectedAndUnselectedRemainDistinct() {
        assertTrue(ServiceProbeStatus.label(true,false,"VOICE_NO_UDP").contains("STUN-узел не ответил"))
        assertEquals("не выбрано",ServiceProbeStatus.label(false,false,"VOICE_CAUTION"))
        assertEquals("проверка не прошла",ServiceProbeStatus.label(true,false,null))
    }
    @Test fun strictVoiceQualityPolicyIsNotWeakened() {
        assertEquals(CallTransportPolicy.Grade.CAUTION,CallTransportPolicy.assess(20,List(20){400},true,800).grade)
        assertEquals(CallTransportPolicy.Grade.LOSS,CallTransportPolicy.assess(20,List(18){100},true,800).grade)
        assertEquals(CallTransportPolicy.Grade.UNKNOWN,CallTransportPolicy.assess(20,List(20){100},false,800).grade)
    }
    @Test fun testerStartsStabilityBeforeBlockingHttpsAndKeepsStrictResults() {
        val f=listOf(File("src/main/java/com/vlesscardvpn/core/Tester.kt"),File("app/src/main/java/com/vlesscardvpn/core/Tester.kt")).first {it.exists()}
        val method=f.readText().substringAfter("    fun probeSocks(").substringBefore("    // Media downloads")
        assertTrue(method.indexOf("val stabilityF =") in 0 until method.indexOf("var best = Long.MAX_VALUE"))
        assertTrue(method.contains("StabilitySeriesProbe.collect"));assertTrue(method.contains("VoiceAvailabilityProbe.check"))
        assertTrue(method.contains("relaxedCalls.set(summary.ignoringLatency)"))
    }
}
