package com.vlesscardvpn.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class YouTubeLoadingR226Test {
    private val url="https://rr1.googlevideo.com/videoplayback?sig=fixture"
    private fun video(n:Int=YouTubeMediaProbe.STABLE_BYTES)=ByteArray(n).apply { if(n>=12) "ftyp".toByteArray().copyInto(this,4) }
    private class Reply(val req:Request,val code:Int,val data:ByteArray,val mime:String="video/mp4",val range:String?=null,val location:String?=null,val encoding:String?=null):Call {
        override fun request()=req
        override fun execute():Response=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Content-Type",mime).apply {
                range?.let { header("Content-Range",it) };location?.let {header("Location",it)};encoding?.let {header("Content-Encoding",it)}
            }.body(data.toResponseBody()).build()
        override fun enqueue(responseCallback:Callback) {throw UnsupportedOperationException()}
        override fun cancel() {}
        override fun isExecuted()=false
        override fun isCanceled()=false
        override fun timeout()=Timeout()
        override fun clone():Call=Reply(req,code,data,mime,range,location,encoding)
    }
    @Test fun full200BodyWorksWithoutRangeSupport() {
        var count=0
        val r=YouTubeMediaProbe.measureStable(url) {count++;assertEquals("bytes=0-196607",it.header("Range"));Reply(it,200,video())}
        assertEquals(true,r.ok);assertTrue(r.kbps>0);assertEquals(1,count)
    }
    @Test fun valid206ProvesSameThreeWindows() {assertEquals(true,YouTubeMediaProbe.measureStable(url) {Reply(it,206,video(),range="bytes 0-196607/1000000")}.ok)}
    @Test fun firstWindowOnlyIsNotStableLoading() {assertEquals(false,YouTubeMediaProbe.measureStable(url) {Reply(it,200,video(131072))}.ok)}
    @Test fun lastWindowTruncationDoesNotPass() {assertEquals("YT_MEDIA_TRUNCATED",YouTubeMediaProbe.measureStable(url) {Reply(it,200,video(196607))}.reason)}
    @Test fun malformed206IsRejected() {assertEquals("YT_RANGE_INVALID",YouTubeMediaProbe.measureStable(url) {Reply(it,206,video(),range="bytes 1-196608/1000000")}.reason)}
    @Test fun smallRangeCannotProveLargeBody() {assertEquals(false,YouTubeMediaProbe.measureStable(url) {Reply(it,206,video(),range="bytes 0-131071/1000000")}.ok)}
    @Test fun htmlCannotImpersonateVideo() {assertEquals("YT_CONTAINER_INVALID",YouTubeMediaProbe.measureStable(url) {Reply(it,200,ByteArray(196608){60})}.reason)}
    @Test fun audioIsNotVideoEvidence() {assertNull(YouTubeMediaProbe.measureStable(url) {Reply(it,200,video(),mime="audio/mp4")}.ok)}
    @Test fun compressedBodyIsNotRawVideoProof() {assertNull(YouTubeMediaProbe.measureStable(url) {Reply(it,200,video(),encoding="gzip")}.ok)}
    @Test fun expiryIsUnknownNotRouteFailure() {for(code in listOf(403,410)) {val r=YouTubeMediaProbe.measureStable(url) {Reply(it,code,byteArrayOf())};assertNull(r.ok);assertEquals("YT_MEDIA_RESTRICTED",r.reason)}}
    @Test fun rateLimitIsUnknownNotRouteFailure() {val r=YouTubeMediaProbe.measureStable(url) {Reply(it,429,byteArrayOf())};assertNull(r.ok);assertEquals("YT_RATE_LIMITED",r.reason)}
    @Test fun safeCdnRedirectCanLoadVideo() {
        val urls=mutableListOf<String>()
        val r=YouTubeMediaProbe.measureStable(url) {urls+=it.url.toString();if(urls.size==1) Reply(it,302,byteArrayOf(),location="https://rr2.googlevideo.com/videoplayback?sig=next") else Reply(it,200,video())}
        assertEquals(true,r.ok);assertEquals(2,urls.size)
    }
    @Test fun redirectCannotEscapeToForeignHost() {var n=0;val r=YouTubeMediaProbe.measureStable(url) {n++;Reply(it,302,byteArrayOf(),location="https://googlevideo.com.evil.test/videoplayback")};assertNull(r.ok);assertEquals("YT_REDIRECT",r.reason);assertEquals(1,n)}
    @Test fun redirectCannotDowngradeOrUseCredentials() {for(target in listOf("http://rr2.googlevideo.com/videoplayback","https://user@rr2.googlevideo.com/videoplayback","https://rr2.googlevideo.com:8443/videoplayback")) {var n=0;assertNull(YouTubeMediaProbe.measureStable(url) {n++;Reply(it,302,byteArrayOf(),location=target)}.ok);assertEquals(1,n)}}
    @Test fun redirectLoopIsBounded() {var n=0;val r=YouTubeMediaProbe.measureStable(url) {n++;Reply(it,302,byteArrayOf(),location=url)};assertNull(r.ok);assertEquals(3,n)}
    @Test fun cancelledProbeDoesNotRequest() {Thread.currentThread().interrupt();try {assertEquals("CANCELLED",YouTubeMediaProbe.measureStable(url) {error("no request")}.reason)} finally {Thread.interrupted()}}
    @Test fun mediaUsesSameBrowserHeadersAsDiscovery() {assertEquals(true,YouTubeMediaProbe.measureStable(url) {assertEquals(YouTubeMediaProbe.USER_AGENT,it.header("User-Agent"));assertEquals(YouTubeMediaProbe.WATCH,it.header("Referer"));assertEquals("identity",it.header("Accept-Encoding"));Reply(it,200,video())}.ok)}
    @Test fun comparisonStillNeedsOnly128KiB() {assertEquals(true,YouTubeMediaProbe.measure(url) {assertEquals("bytes=0-131071",it.header("Range"));Reply(it,200,video(131072))}.ok)}
    @Test fun webmHeaderIsAccepted() {val b=video();byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()).copyInto(b);assertEquals(true,YouTubeMediaProbe.measureStable(url) {Reply(it,200,b,mime="video/webm")}.ok)}
    @Test fun untrustedSourceNeverMakesRequest() {assertNull(YouTubeMediaProbe.measureStable("https://example.org/videoplayback") {error("no request")}.ok)}
}
