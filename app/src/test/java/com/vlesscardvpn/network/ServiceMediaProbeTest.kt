package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ServiceMediaProbeTest {
    private val url = "https://rr1---sn-test.googlevideo.com/videoplayback?expire=123&sig=example"
    private fun html(extra: String = "", mime: String = "video/mp4", source: String = url) = """<script>var ytInitialPlayerResponse = {"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"mimeType":"$mime","url":"$source"}]},"extra":"$extra"};</script>"""
    private class FakeCall(private val req: Request, private val code: Int, private val mime: String, private val body: ByteArray) : Call {
        private var cancelled = false
        override fun request() = req
        override fun execute(): Response = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", mime).apply { if(code==206) header("Content-Range","bytes 0-${body.size-1}/${body.size}") }.body(body.toResponseBody(mime.toMediaType())).build()
        override fun enqueue(responseCallback: Callback) { throw UnsupportedOperationException() }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = false
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(req, code, mime, body)
    }
    @Test fun r216DiscoveryExplainsConsentCipherHttpAndFormatWithoutPassing() {
        val missing=listOf("<html>consent</html>",html().replace("\"url\"","\"signatureCipher\""))
        for(body in missing) {
            val r=YouTubeMediaProbe.check {FakeCall(it,200,"text/html",body.toByteArray())}
            assertNull(r.ok);assertEquals("YT_NO_DIRECT_MEDIA",r.reason)
        }
        assertEquals("YT_HTTP",YouTubeMediaProbe.discoverDetailed {FakeCall(it,429,"text/html",byteArrayOf())}.reason)
        assertEquals("YT_FORMAT",YouTubeMediaProbe.discoverDetailed {FakeCall(it,200,"image/jpeg",byteArrayOf())}.reason)
        assertEquals(url,YouTubeMediaProbe.discover {FakeCall(it,200,"text/html",html().toByteArray())})
    }
    @Test fun playerResponseHandlesBracesAndEscapedQuotes() {
        assertEquals(url, YouTubeMediaProbe.mediaUrl(html("} \\\" {")))
        assertNull(YouTubeMediaProbe.playerJson("ytInitialPlayerResponse = {\"x\":{\"unfinished\":true}"))
    }
    @Test fun imagesAudioCipherAndConsentAreNotVideoEvidence() {
        assertNull(YouTubeMediaProbe.mediaUrl(html(mime = "image/jpeg")))
        assertNull(YouTubeMediaProbe.mediaUrl(html(mime = "audio/mp4")))
        assertNull(YouTubeMediaProbe.mediaUrl(html().replace("\"url\"", "\"signatureCipher\"")))
        assertNull(YouTubeMediaProbe.mediaUrl(html().replace("\"OK\"", "\"LOGIN_REQUIRED\"")))
        assertNull(YouTubeMediaProbe.mediaUrl("<html>captcha consent</html>"))
    }
    @Test fun mediaHostAndSchemeAreStrict() {
        assertTrue(YouTubeMediaProbe.allowedUrl(url))
        listOf("http://r.googlevideo.com/videoplayback", "https://googlevideo.com.evil.test/videoplayback",
            "https://r.googlevideo.com:8443/videoplayback", "https://u:p@r.googlevideo.com/videoplayback",
            "https://127.0.0.1/videoplayback", "https://r.googlevideo.com/not-media").forEach {
            assertFalse(it, YouTubeMediaProbe.allowedUrl(it))
        }
    }
    @Test fun realVideoSampleRequires128KiBNotTinySuccess() {
        var range = ""
        val result = YouTubeMediaProbe.measure(url) { req ->
            range = req.header("Range").orEmpty()
            FakeCall(req, 206, "video/mp4", ByteArray(YouTubeMediaProbe.SAMPLE_BYTES).apply { "ftyp".toByteArray().copyInto(this,4) })
        }
        assertEquals(true, result.ok); assertTrue(result.kbps > 0)
        assertEquals("bytes=0-131071", range)
        assertEquals(false, YouTubeMediaProbe.measure(url) { FakeCall(it, 206, "video/mp4", ByteArray(16384)) }.ok)
    }
    @Test fun expiredSignedUrlsAndHtmlAreUnknownNotDeadServers() {
        for (code in listOf(302, 403, 410, 429)) assertNull(YouTubeMediaProbe.measure(url) { FakeCall(it, code, "video/mp4", ByteArray(32)) }.ok)
        assertNull(YouTubeMediaProbe.measure(url) { FakeCall(it, 200, "text/html", ByteArray(200000)) }.ok)
        var called = false
        assertNull(YouTubeMediaProbe.measure("http://localhost/") { called = true; FakeCall(it, 200, "video/mp4", ByteArray(200000)) }.ok)
        assertFalse(called)
    }
    @Test fun unknownDiscoveryNeverBecomesPassed() {
        assertNull(YouTubeMediaProbe.check { FakeCall(it, 200, "text/html", "consent".toByteArray()) }.ok)
        assertFalse(YouTubeMediaProbe.freshProof(0, 100))
        assertFalse(YouTubeMediaProbe.freshProof(200, 100))
        assertFalse(YouTubeMediaProbe.freshProof(100, 600100))
        assertTrue(YouTubeMediaProbe.freshProof(100, 200))
    }
    @Test fun mtprotoRequestUsesFreshNonceAndUnencryptedFraming() {
        val nonce = ByteArray(16) { it.toByte() }
        val req = TelegramProbe.request(nonce, 1700000000123)
        assertEquals(42, req.size); assertEquals(239, req[0].toInt() and 255); assertEquals(10, req[1].toInt())
        val b = ByteBuffer.wrap(req, 2, 40).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0L, b.long); assertEquals(0, (b.long and 3).toInt())
        assertEquals(20, b.int); assertEquals(0xbe7e8ef1.toInt(), b.int)
        val got = ByteArray(16); b.get(got); assertArrayEquals(nonce, got)
    }
    private fun response(nonce: ByteArray): ByteArray = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(nonce).put(ByteArray(16) { 1 })
        .put(8).put(ByteArray(8).apply { this[7]=15 }).put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(123).array()
    @Test fun mtprotoRequiresCorrectNonceAndCompleteStructuredResponse() {
        val nonce = ByteArray(16) { it.toByte() }; val good = response(nonce)
        assertTrue(TelegramProbe.validResponse(good, nonce))
        assertFalse(TelegramProbe.validResponse(good, ByteArray(16)))
        assertFalse(TelegramProbe.validResponse(good.copyOf(76), nonce))
        assertFalse(TelegramProbe.validResponse("HTTP/1.1 200 OK".toByteArray(), nonce))
        assertFalse(TelegramProbe.validResponse(ByteArray(8192), nonce))
        good[0] = 1; assertFalse(TelegramProbe.validResponse(good, nonce))
    }
    @Test fun telegramInsufficientBudgetIsUnknownAndNoDirectFallback() {
        assertNull(TelegramProbe.check(0))
        assertNull(TelegramProbe.check(65536))
        assertNull(TelegramProbe.check(1080, 50))
    }
    @Test fun fakeVideoMimeWithHtmlBodyCannotPassMediaProbe() {
        assertEquals(false,YouTubeMediaProbe.measure(url) { FakeCall(it,200,"video/mp4",("<html>"+"x".repeat(200000)).toByteArray()) }.ok)
    }
}
