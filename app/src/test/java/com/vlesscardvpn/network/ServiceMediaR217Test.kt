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

class ServiceMediaR217Test {
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

    private fun enc(s:String)=java.net.URLEncoder.encode(s,"UTF-8")
    @Test fun plaintextSignedCipherIsSupportedWithoutDeciphering() {
        val source="https://rr1.googlevideo.com/videoplayback?expire=123"
        val cipher="url="+enc(source)+"&sig="+enc("abc+def")+"&sp=sig"
        val got=YouTubeMediaProbe.signedCipher(cipher)
        assertNotNull(got);assertTrue(got!!.contains("sig=abc%2Bdef"))
        assertTrue(YouTubeMediaProbe.allowedUrl(got))
        val body=html().replace("\"url\":\"$url\"","\"signatureCipher\":\"$cipher\"")
        assertEquals(got,YouTubeMediaProbe.mediaUrl(body))
    }
    @Test fun encryptedCipherDuplicateFieldsAndUntrustedUrlsStayUnknown() {
        val base="url="+enc(url)+"&sig=abc"
        assertNull(YouTubeMediaProbe.signedCipher(base+"&s=encrypted"))
        assertNull(YouTubeMediaProbe.signedCipher(base+"&url="+enc(url)))
        assertNull(YouTubeMediaProbe.signedCipher(base+"&sp=redirect"))
        assertNull(YouTubeMediaProbe.signedCipher("url="+enc("https://evil.test/videoplayback")+"&sig=x"))
    }
    @Test fun triesAnotherRealVideoWhenFirstSignedFormatExpired() {
        val second="https://rr2.googlevideo.com/videoplayback?expire=456"
        val page="ytInitialPlayerResponse = "+org.json.JSONObject().put("playabilityStatus",org.json.JSONObject().put("status","OK"))
            .put("streamingData",org.json.JSONObject().put("formats",org.json.JSONArray()
                .put(org.json.JSONObject().put("mimeType","video/mp4").put("url",url))
                .put(org.json.JSONObject().put("mimeType","video/mp4").put("url",second)))).toString()
        val requests=mutableListOf<String>()
        val result=YouTubeMediaProbe.check {req->
            requests+=req.url.toString()
            when(req.url.toString()) {
                YouTubeMediaProbe.WATCH->FakeCall(req,200,"text/html",page.toByteArray())
                url->FakeCall(req,403,"text/html",byteArrayOf())
                else->FakeCall(req,206,"video/mp4",ByteArray(YouTubeMediaProbe.SAMPLE_BYTES).apply{"ftyp".toByteArray().copyInto(this,4)})
            }
        }
        assertEquals(true,result.ok);assertEquals(listOf(YouTubeMediaProbe.WATCH,url,second),requests)
    }
    @Test fun consentAndAudioDoNotBecomeSuccess() {
        assertNull(YouTubeMediaProbe.check{FakeCall(it,200,"text/html","consent".toByteArray())}.ok)
        assertTrue(YouTubeMediaProbe.mediaUrls(html(mime="audio/mp4")).isEmpty())
    }
}
