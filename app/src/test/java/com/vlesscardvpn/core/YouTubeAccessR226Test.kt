package com.vlesscardvpn.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class YouTubeAccessR226Test {
    private class Reply(val req:Request,val code:Int):Call {
        override fun request()=req
        override fun execute()=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(byteArrayOf().toResponseBody()).build()
        override fun enqueue(responseCallback:Callback) {throw UnsupportedOperationException()}
        override fun cancel() {}
        override fun isExecuted()=false
        override fun isCanceled()=false
        override fun timeout()=Timeout()
        override fun clone():Call=Reply(req,code)
    }
    @Test fun one204RequestPasses() {var count=0;val r=YouTubeAccessProbe.check {count++;assertEquals(YouTubeAccessProbe.URL,it.url.toString());Reply(it,204)};assertEquals(true,r.ok);assertEquals(1,count);assertEquals(0,r.kbps)}
    @Test fun noVideoOrRangeIsRequested() {YouTubeAccessProbe.check {assertNull(it.header("Range"));assertFalse(it.url.toString().contains("videoplayback"));assertEquals("GET",it.method);Reply(it,204)}}
    @Test fun noCachedProofRequested() {YouTubeAccessProbe.check {assertEquals("no-cache, no-store",it.header("Cache-Control"));Reply(it,204)}}
    @Test fun http200PageDoesNotImpersonateExpected204() {assertEquals(false,YouTubeAccessProbe.check {Reply(it,200)}.ok)}
    @Test fun unexpectedHttpDoesNotPass() {for(code in listOf(400,403,404,500,503)) {val r=YouTubeAccessProbe.check {Reply(it,code)};assertEquals(false,r.ok);assertEquals("YT_ACCESS_HTTP",r.reason)}}
    @Test fun rateLimitIsUnknown() {assertNull(YouTubeAccessProbe.check {Reply(it,429)}.ok)}
    @Test fun redirectsAreNotFollowedByThisProbe() {var n=0;val r=YouTubeAccessProbe.check {n++;Reply(it,302)};assertNull(r.ok);assertEquals(1,n)}
    @Test fun foreignFinalUrlCannotPass() {assertNull(YouTubeAccessProbe.check {Reply(it.newBuilder().url("https://example.org/generate_204").build(),204)}.ok)}
    @Test fun timeoutIsUnknownNotPass() {val r=YouTubeAccessProbe.check {throw java.net.SocketTimeoutException()};assertNull(r.ok);assertEquals("TIMEOUT",r.reason)}
    @Test fun cancellationMakesNoRequest() {Thread.currentThread().interrupt();try {assertEquals("CANCELLED",YouTubeAccessProbe.check {error("must not request")}.reason)} finally {Thread.interrupted()}}
}
