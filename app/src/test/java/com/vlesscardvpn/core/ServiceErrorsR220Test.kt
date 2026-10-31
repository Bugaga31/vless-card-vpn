package com.vlesscardvpn.core

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ServiceErrorsR220Test {
    private val media="https://rr1.googlevideo.com/videoplayback?sig=test"
    private class FixtureCall(val req:Request,val code:Int=200,val data:String="",val mime:String="application/json",val failure:Exception?=null):Call {
        override fun request()=req
        override fun execute():Response {
            failure?.let {throw it}
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Content-Type",mime).body(data.toResponseBody(mime.toMediaType())).build()
        }
        override fun enqueue(responseCallback:Callback) {throw UnsupportedOperationException()}
        override fun cancel() {}
        override fun isExecuted()=false
        override fun isCanceled()=false
        override fun timeout()=Timeout()
        override fun clone():Call=FixtureCall(req,code,data,mime,failure)
    }
    @Test fun discordRequiresExpectedGateway() {
        assertEquals(true,DiscordApiProbe.check {FixtureCall(it,data="""{"url":"wss://gateway.discord.gg"}""")}.ok)
        assertEquals(true,DiscordApiProbe.check {FixtureCall(it,data="""{"url":"wss://gateway.discord.gg/"}""")}.ok)
    }
    @Test fun discordRateLimitIsNotServerFailureOrSuccess() {
        val r=DiscordApiProbe.check {FixtureCall(it,429,"rate limited")}
        assertNull(r.ok);assertEquals("DISCORD_HTTP",r.reason)
    }
    @Test fun discordMalformedAndForeignGatewaysStayUnknown() {
        for(body in listOf("<html>challenge</html>","{}","""{"url":"wss://gateway.discord.gg.evil.test"}""")) {
            val r=DiscordApiProbe.check {FixtureCall(it,data=body)}
            assertNull(r.ok);assertEquals("DISCORD_FORMAT",r.reason)
        }
    }
    @Test fun discordOversizeBodyIsBoundedAndUnknown() {
        val r=DiscordApiProbe.check {FixtureCall(it,data="x".repeat(8193))}
        assertNull(r.ok);assertEquals("DISCORD_FORMAT",r.reason)
    }
    @Test fun discordTimeoutHasExplicitReason() {
        val r=DiscordApiProbe.check {FixtureCall(it,failure=java.net.SocketTimeoutException())}
        assertNull(r.ok);assertEquals("TIMEOUT",r.reason)
    }
    @Test fun discordIoErrorDoesNotBecomeDefinitiveFail() {
        val r=DiscordApiProbe.check {FixtureCall(it,failure=java.io.IOException("private endpoint"))}
        assertNull(r.ok);assertEquals("ERROR",r.reason);assertFalse(r.toString().contains("private endpoint"))
    }
    @Test fun youtubeMediaTimeoutStaysUnknown() {
        val r=YouTubeMediaProbe.measure(media) {FixtureCall(it,failure=java.net.SocketTimeoutException())}
        assertNull(r.ok);assertEquals("TIMEOUT",r.reason)
    }
    @Test fun youtubeMediaIoErrorStaysUnknown() {
        val r=YouTubeMediaProbe.measure(media) {FixtureCall(it,failure=java.io.IOException())}
        assertNull(r.ok);assertEquals("ERROR",r.reason)
    }
    @Test fun timeoutDoesNotStartMoreFormatsAfterDeadline() {
        val second="https://rr2.googlevideo.com/videoplayback?sig=test"
        val page="""ytInitialPlayerResponse={"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"mimeType":"video/mp4","url":"$media"},{"mimeType":"video/mp4","url":"$second"}]}}"""
        var downloads=0
        val r=YouTubeMediaProbe.check {req->
            if(req.url.toString()==YouTubeMediaProbe.WATCH) FixtureCall(req,data=page,mime="text/html")
            else {downloads++;FixtureCall(req,failure=java.net.SocketTimeoutException())}
        }
        assertNull(r.ok);assertEquals("TIMEOUT",r.reason);assertEquals(1,downloads)
    }
    @Test fun invalidVideoBytesRemainFailure() {
        val r=YouTubeMediaProbe.measure(media) {FixtureCall(it,data="<html>fake video</html>",mime="video/mp4")}
        assertEquals(false,r.ok)
    }
    @Test fun cancellationStaysUnknownAndKeepsInterrupt() {
        Thread.currentThread().interrupt()
        try {
            val d=DiscordApiProbe.check {FixtureCall(it,failure=java.io.InterruptedIOException())}
            val y=YouTubeMediaProbe.measure(media) {FixtureCall(it,failure=java.io.InterruptedIOException())}
            assertNull(d.ok);assertEquals("CANCELLED",d.reason)
            assertNull(y.ok);assertEquals("CANCELLED",y.reason);assertTrue(Thread.currentThread().isInterrupted)
        } finally {Thread.interrupted()}
    }
    @Test fun labelsExplainUnknownWithoutCallingItSuccess() {
        assertTrue(ServiceProbeStatus.label(true,null,"DISCORD_FORMAT").contains("подтверждения нет"))
        assertTrue(ServiceProbeStatus.label(true,null,"DISCORD_HTTP").contains("не подтвердил"))
        assertEquals("не выбрано",ServiceProbeStatus.label(false,null,"TIMEOUT"))
    }
}
