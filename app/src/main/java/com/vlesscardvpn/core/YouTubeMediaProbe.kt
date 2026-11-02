package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** A thumbnail, HTML page or 204 is not evidence that video can be downloaded.
 * No login, JS execution, signature deciphering or persistent signed URLs. Unknown stays unknown.
 */
object YouTubeMediaProbe {
    const val WATCH = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    const val SAMPLE_BYTES = 131072
    internal const val MAX_PAGE_BYTES = 2 * 1048576
    const val PROOF_PREFS = "youtube_media_proof_v2"
    const val STABLE_BYTES = SAMPLE_BYTES + 2 * 32768
    internal const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    data class Result(val ok: Boolean?, val kbps: Int = 0,val reason:String="")
    data class Discovery(val url:String?,val reason:String="",val alternatives:List<String> = emptyList())

    fun freshProof(at: Long, now: Long) = at > 0 && now >= at && now - at < 10 * 60000L
    fun allowedUrl(value: String): Boolean {
        val u = value.toHttpUrlOrNull() ?: return false
        return u.isHttps && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() &&
            u.host.endsWith(".googlevideo.com") && u.encodedPath == "/videoplayback"
    }

    /** Balanced JSON scanner: braces inside strings must not terminate the player response. */
    internal fun playerJson(html: String): String? {
        if (html.length > MAX_PAGE_BYTES) return null
        val marker = html.indexOf("ytInitialPlayerResponse")
        if (marker < 0) return null
        val begin = html.indexOf('{', marker)
        if (begin < 0 || begin - marker > 100) return null
        var depth = 0; var quoted = false; var escaped = false
        for (i in begin until html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return html.substring(begin, i + 1) }
            }
        }
        return null
    }

    internal fun discoveryReason(html:String):String = runCatching {
        val root=JSONObject(playerJson(html) ?: return "YT_NO_DIRECT_MEDIA")
        when(root.optJSONObject("playabilityStatus")?.optString("status")) {
            "LOGIN_REQUIRED" -> "YT_LOGIN_REQUIRED"
            "UNPLAYABLE", "ERROR", "LIVE_STREAM_OFFLINE" -> "YT_UNPLAYABLE"
            else -> {
                val streams=root.optJSONObject("streamingData")
                val encrypted=listOf("formats","adaptiveFormats").any {key->
                    val formats=streams?.optJSONArray(key)
                    (0 until (formats?.length() ?: 0)).any {i->
                        val f=formats?.optJSONObject(i)
                        f?.optString("mimeType").orEmpty().startsWith("video/") &&
                            f?.optString("url").orEmpty().isBlank() &&
                            f?.optString("signatureCipher",f.optString("cipher")).orEmpty().split('&').any {it.substringBefore('=')=="s"}
                    }
                }
                if(encrypted) "YT_SIGNATURE_UNSUPPORTED" else "YT_NO_DIRECT_MEDIA"
            }
        }
    }.getOrDefault("YT_NO_DIRECT_MEDIA")

    internal fun mediaUrl(html:String):String? = mediaUrls(html).firstOrNull()
    internal fun mediaUrls(html:String):List<String> = runCatching {
        val root=JSONObject(playerJson(html) ?: return emptyList())
        if(root.optJSONObject("playabilityStatus")?.optString("status")!="OK") return emptyList()
        val data=root.optJSONObject("streamingData") ?: return emptyList()
        val urls=mutableListOf<String>()
        for(key in listOf("formats","adaptiveFormats")) {
            val list=data.optJSONArray(key) ?: continue
            for(i in 0 until list.length()) {
                val f=list.optJSONObject(i) ?: continue
                if(!f.optString("mimeType").startsWith("video/")) continue
                val direct=f.optString("url")
                val candidate=if(direct.isNotBlank()) direct else signedCipher(f.optString("signatureCipher",f.optString("cipher")))
                if(candidate!=null && allowedUrl(candidate) && candidate !in urls) urls+=candidate
                if(urls.size==3) return urls
            }
        }
        urls
    }.getOrDefault(emptyList())

    /** Already signed URL only; encrypted `s` requires unsupported JS deciphering. */
    internal fun signedCipher(value:String):String? = runCatching {
        if(value.length>16384) return null
        val parts=value.split('&').filter {it.isNotEmpty()}.map {part->
            val pair=part.split('=',limit=2)
            java.net.URLDecoder.decode(pair[0],"UTF-8") to java.net.URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
        }
        if(parts.map{it.first}.distinct().size!=parts.size) return null
        val fields=parts.toMap()
        if(fields.containsKey("s")) return null
        val url=fields["url"] ?: return null
        if(!allowedUrl(url)) return null
        val sig=fields["sig"] ?: fields["signature"] ?: return null
        if(sig.isBlank() || sig.length>2048) return null
        val name=fields["sp"] ?: "signature"
        if(name !in setOf("sig","signature","lsig")) return null
        val parsed=url.toHttpUrlOrNull() ?: return null
        if(parsed.queryParameter(name)!=null) return null
        parsed.newBuilder().addQueryParameter(name,sig).build().toString()
    }.getOrNull()

    fun discover(call:(Request)->Call):String? = discoverDetailed(call).url
    fun discoverDetailed(call:(Request)->Call):Discovery = try {
        call(Request.Builder().url(WATCH).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if(r.code!=200) return Discovery(null,"YT_HTTP")
            if(!r.header("Content-Type").orEmpty().startsWith("text/html",true)) return Discovery(null,"YT_FORMAT")
            val input=r.body?.byteStream() ?: return Discovery(null,"YT_FORMAT")
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
            while(out.size()<MAX_PAGE_BYTES) {
                if(Thread.currentThread().isInterrupted) return Discovery(null,"CANCELLED")
                val n=input.read(buf,0,minOf(buf.size,MAX_PAGE_BYTES-out.size()))
                if(n<0) break
                out.write(buf,0,n)
                if(out.size()%32768<8192) {
                    val page=out.toString("UTF-8")
                    mediaUrls(page).takeIf {it.isNotEmpty()}?.let {return Discovery(it.first(),alternatives=it.drop(1))}
                    // A complete denied/unsupported player cannot gain media from later unrelated HTML.
                    if(playerJson(page)!=null) return Discovery(null,discoveryReason(page))
                }
            }
            mediaUrls(out.toString("UTF-8")).takeIf {it.isNotEmpty()}?.let {Discovery(it.first(),alternatives=it.drop(1))} ?: Discovery(null,discoveryReason(out.toString("UTF-8")))
        }
    } catch(_:java.net.SocketTimeoutException) {Discovery(null,"TIMEOUT")}
      catch(_:Exception) {Discovery(null,if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")}

    /** Same bounded download path for comparison (128 KiB) and loading (192 KiB).
     * An HTTP 200 that ignores Range can prove sequential loading on this body;
     * reopening it at a nonzero offset would incorrectly test Range support instead.
     */
    fun measure(url:String,call:(Request)->Call):Result = measureWindows(url,listOf(SAMPLE_BYTES),call)

    private fun measureWindows(url:String,windows:List<Int>,call:(Request)->Call):Result {
        if(!allowedUrl(url)) return Result(null,reason="YT_FORMAT")
        val totalRequired=windows.sum()
        var current=url
        var redirects=0
        var started=System.nanoTime()
        try {
            while(true) {
                if(Thread.currentThread().isInterrupted) return Result(null,reason="CANCELLED")
                val response=call(Request.Builder().url(current)
                    .header("Range","bytes=0-${totalRequired-1}")
                    .header("Accept-Encoding","identity")
                    .header("User-Agent",USER_AGENT).header("Referer",WATCH).build()).execute()
                response.use { r ->
                    if(r.code in setOf(301,302,303,307,308)) {
                        val next=r.header("Location")?.let {current.toHttpUrlOrNull()?.resolve(it)?.toString()}
                        if(redirects>=2 || next==null || !allowedUrl(next)) return Result(null,reason="YT_REDIRECT")
                        current=next;redirects++
                    } else {
                        if(r.code==429) return Result(null,reason="YT_RATE_LIMITED")
                        if(r.code in setOf(403,410)) return Result(null,reason="YT_MEDIA_RESTRICTED")
                        if(r.code !in listOf(200,206)) return Result(null,reason="YT_HTTP")
                        if(!r.header("Content-Type").orEmpty().startsWith("video/",true)) return Result(null,reason="YT_FORMAT")
                        if(!TransferProof.rangeValid(r.code,r.header("Content-Range"),totalRequired)) return Result(false,reason="YT_RANGE_INVALID")
                        if(!r.header("Content-Encoding").orEmpty().let {it.isEmpty() || it.equals("identity",true)}) return Result(null,reason="YT_FORMAT")
                        val input=r.body?.byteStream() ?: return Result(null,reason="YT_FORMAT")
                        val buf=ByteArray(16384);val prefix=ByteArray(12);var prefixSize=0
                        var slowest=Int.MAX_VALUE
                        for(size in windows) {
                            var got=0
                            while(got<size) {
                                if(Thread.currentThread().isInterrupted) return Result(null,reason="CANCELLED")
                                val n=input.read(buf,0,minOf(buf.size,size-got))
                                if(n<=0) return Result(false,reason="YT_MEDIA_TRUNCATED")
                                if(prefixSize<prefix.size) {
                                    val count=minOf(n,prefix.size-prefixSize)
                                    buf.copyInto(prefix,prefixSize,0,count);prefixSize+=count
                                    if(prefixSize==prefix.size && !TransferProof.videoHeader(prefix,r.header("Content-Type").orEmpty()))
                                        return Result(false,reason="YT_CONTAINER_INVALID")
                                }
                                got+=n
                            }
                            val now=System.nanoTime()
                            slowest=minOf(slowest,TransferProof.rate(got,now-started));started=now
                        }
                        return Result(true,slowest,reason="YT_MEDIA_LOADED")
                    }
                }
            }
        } catch(_:java.io.InterruptedIOException) {
            return Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT")
        } catch(_:Exception) {
            return Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")
        }
    }

    internal fun continuationRange(value:String?,offset:Int,size:Int):Boolean {
        val m=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return false
        val a=m.groupValues[1].toLongOrNull() ?: return false
        val b=m.groupValues[2].toLongOrNull() ?: return false
        val total=m.groupValues[3].toLongOrNull() ?: return false
        return a==offset.toLong() && b==offset.toLong()+size-1 && total>b
    }
    /** Three sequential body windows in ONE response. Not a decoder/player or long-term playback proof. */
    fun measureStable(url:String,call:(Request)->Call):Result = measureWindows(url,listOf(SAMPLE_BYTES,32768,32768),call)

    fun check(call:(Request)->Call):Result {
        val discovery=discoverDetailed(call)
        val first=discovery.url ?: return Result(null,reason=discovery.reason)
        var last=Result(null,reason=discovery.reason)
        for(url in (listOf(first)+discovery.alternatives).distinct().take(3)) {
            if(Thread.currentThread().isInterrupted) return Result(null,reason="CANCELLED")
            last=measureStable(url,call)
            if(last.ok==true || last.reason in setOf("TIMEOUT","CANCELLED")) return last
        }
        return last
    }
}
