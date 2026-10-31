package com.vlesscardvpn.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okio.Timeout
import java.io.File

class ServiceExpansionR223Test {
    private fun train(r:List<Int>,complete:Boolean=true,sent:Int=4)=UdpCallProbe.Train(sent,r,complete,100)
    @Test fun udpAt400msDoesNotFailOldQualityThreshold() {
        assertEquals(CallTransportPolicy.Grade.CAUTION,CallTransportPolicy.assess(20,List(20){400},true,800).grade)
        assertEquals(true,VoiceAvailabilityProbe.assess(train(List(4){400}),800).ok)
    }
    @Test fun partialUdpEvidenceReportsLossSeparately() {
        val r=VoiceAvailabilityProbe.assess(train(listOf(100,200)),800)
        assertEquals(true,r.ok);assertEquals("VOICE_AVAILABLE_LOSS",r.reason)
    }
    @Test fun singleResponseIsInsufficient() {assertNull(VoiceAvailabilityProbe.assess(train(listOf(100)),800).ok)}
    @Test fun incompleteSeriesDoesNotPass() {assertNull(VoiceAvailabilityProbe.assess(train(List(4){100},false),800).ok)}
    @Test fun cancelledSenderDoesNotPass() {assertNull(VoiceAvailabilityProbe.assess(train(List(4){100},true,3),800).ok)}
    @Test fun slowUdpOnlyPermitsLatencyConsent() {
        val r=VoiceAvailabilityProbe.assess(train(List(4){900}),800);assertEquals(false,r.ok);assertEquals(true,r.ignoringLatency)
    }
    @Test fun invalidRttsCannotCreateSuccess() {assertNull(VoiceAvailabilityProbe.assess(train(listOf(0,-1)),800).ok)}
    @Test fun alternateTargetIsUsedAfterNoResponses() {
        val hosts=mutableListOf<String>()
        val r=VoiceAvailabilityProbe.check(800,{4000}) {target,allowance->
            hosts+=target.host;assertTrue(allowance<=2000)
            train(if(hosts.size==1) emptyList() else List(4){100})
        }
        assertEquals(VoiceAvailabilityProbe.targets.map {it.host},hosts);assertEquals(true,r.ok)
    }
    @Test fun successfulPrimaryDoesNotSpendFallback() {
        var n=0;assertEquals(true,VoiceAvailabilityProbe.check(800,{4000}) {_,_->n++;train(List(4){100})}.ok);assertEquals(1,n)
    }
    @Test fun noTargetsAnswerIsUnknownNotFakeSuccess() {assertNull(VoiceAvailabilityProbe.check(800,{4000}) {_,_->train(emptyList())}.ok)}
    @Test fun insufficientBudgetDoesNotStartSocket() {
        var n=0;val r=VoiceAvailabilityProbe.check(800,{249}) {_,_->n++;train(List(4){100})}
        assertNull(r.ok);assertEquals(0,n);assertEquals("TIMEOUT",r.reason)
    }
    @Test fun cancellationIsPreserved() {
        Thread.currentThread().interrupt()
        try {assertEquals("CANCELLED",VoiceAvailabilityProbe.check(800,{4000}) {_,_->error("must not run")}.reason)} finally {Thread.interrupted()}
    }
    private val page="""<html><title>Instagram</title><script src="https://static.cdninstagram.com/a.js"></script></html>"""
    @Test fun expectedInstagramPublicPagePasses() {assertEquals(true,InstagramWebProbe.inspect(200,"text/html; charset=utf-8",null,page).ok)}
    @Test fun foreignPageDoesNotPass() {assertNull(InstagramWebProbe.inspect(200,"text/html",null,"<title>Portal</title>").ok)}
    @Test fun instagramNameOnlyDoesNotPass() {assertNull(InstagramWebProbe.inspect(200,"text/html",null,"<title>Instagram</title>").ok)}
    @Test fun loginRedirectIsUnknown() {assertEquals("IG_LOGIN",InstagramWebProbe.inspect(302,"text/html","https://www.instagram.com/accounts/login/",page).reason)}
    @Test fun challengeIsNotAWorkingAccount() {assertNull(InstagramWebProbe.inspect(200,"text/html",null,page+"challenge_required").ok)}
    @Test fun deniedPageIsNotDeadServerDiagnosis() {assertNull(InstagramWebProbe.inspect(429,"text/html",null,page).ok)}
    @Test fun imageCannotPassWebProof() {assertNull(InstagramWebProbe.inspect(200,"image/png",null,page).ok)}
    @Test fun rangeOffsetsAreExact() {
        assertTrue(YouTubeMediaProbe.continuationRange("bytes 131072-163839/1000000",131072,32768))
        for(x in listOf(null,"bytes 0-32767/1000000","bytes 131072-163839/*","bytes 131072-163839/163839","bytes 131072-163840/1000000"))
            assertFalse(YouTubeMediaProbe.continuationRange(x,131072,32768))
    }
    private class Reply(private val req:Request,private val code:Int,private val bytes:ByteArray,private val range:String?):Call {
        override fun request()=req
        override fun execute():Response=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Content-Type","video/mp4").apply {if(range!=null) header("Content-Range",range)}.body(bytes.toResponseBody()).build()
        override fun enqueue(responseCallback:Callback) {throw UnsupportedOperationException()}
        override fun cancel() {}
        override fun isExecuted()=false
        override fun isCanceled()=false
        override fun timeout()=Timeout()
        override fun clone():Call=Reply(req,code,bytes,range)
    }
    private fun media(req:Request,truncated:Boolean=false,ignoreRange:Boolean=false):Call {
        val range=req.header("Range")!!.substringAfter("bytes=").split('-');val start=range[0].toInt();val end=range[1].toInt()
        val data=ByteArray(if(truncated && start>0) 12 else end-start+1)
        if(start==0) {data[4]='f'.code.toByte();data[5]='t'.code.toByte();data[6]='y'.code.toByte();data[7]='p'.code.toByte()}
        return Reply(req,if(ignoreRange) 200 else 206,data,if(ignoreRange) null else "bytes $start-$end/1000000")
    }
    @Test fun threeActualMediaWindowsRequired() {
        val ranges=mutableListOf<String>();val r=YouTubeMediaProbe.measureStable("https://rr1.googlevideo.com/videoplayback") {ranges+=it.header("Range")!!;media(it)}
        assertEquals(true,r.ok);assertEquals(listOf("bytes=0-131071","bytes=131072-163839","bytes=163840-196607"),ranges)
    }
    @Test fun truncatedContinuationFails() {assertEquals(false,YouTubeMediaProbe.measureStable("https://rr1.googlevideo.com/videoplayback") {media(it,truncated=true)}.ok)}
    @Test fun ignoredRangeIsNotContinuityEvidence() {assertNull(YouTubeMediaProbe.measureStable("https://rr1.googlevideo.com/videoplayback") {media(it,ignoreRange=true)}.ok)}
    @Test fun foreignMediaUrlNeverMakesRequest() {
        assertNull(YouTubeMediaProbe.measureStable("https://attacker.invalid/videoplayback") {error("must not request")}.ok)
    }
    @Test fun zapretCommandIsNotPassedToByeDpi() {
        for(s in listOf("nfqws2 --lua-desync=fake","winws.exe --wf-tcp=443","--dpi-desync=fake")) {
            val result=ByeDpiArgs.parse(s);assertTrue(result.isFailure);assertTrue(result.exceptionOrNull()!!.message!!.contains("zapret"))
        }
    }
    @Test fun existingByeDpiStrategiesRemainValid() {ByeDpiArgs.EXAMPLES.forEach {assertTrue(ByeDpiArgs.parse(it).isSuccess)}}
    @Test fun fakeWithoutFoolingConditionsShowsWarning() {assertTrue(ByeDpiArgs.warnings(ByeDpiArgs.parse("-f-1").getOrThrow()).any {it.contains("сломать соединение")})}
    @Test fun newReasonsAreVisible() {
        for(reason in listOf("IG_LOGIN","IG_HTTP","YT_RANGE_UNSUPPORTED","VOICE_LATENCY")) assertFalse(ProbeTaskNotes.label(reason).contains("определённый результат"))
    }
    private fun source(name:String)=listOf(File("src/main/java/com/vlesscardvpn/$name"),File("app/src/main/java/com/vlesscardvpn/$name")).first {it.exists()}.readText()
    @Test fun allNewChecksUseTheCandidateSocksAndSharedDeadline() {
        val s=source("core/Tester.kt");assertTrue(s.contains("InstagramWebProbe.check {boundedCall(mediaClient,it)}"));assertTrue(s.contains("VoiceAvailabilityProbe.check"))
        assertTrue(s.contains("instagramOk=instagramResult.ok"));assertTrue(s.contains("summary.ignoringLatency"))
    }
    @Test fun newInstagramOptionHasStrictGateAndVersionedProof() {
        val s=source("core/AutoCheckProfile.kt");assertTrue(s.contains("(!instagram || p.instagramOk==true)"))
        for(v in listOf("youtube-ranges-v2","udp-availability-v2","instagram-web-v1","telegram-series-v2")) assertTrue(s.contains(v))
    }
    @Test fun rokiSeparatesNewProofSemantics() {assertTrue(source("core/Roki.kt").contains("AutoChecks.key(profile,limit)"))}
}
