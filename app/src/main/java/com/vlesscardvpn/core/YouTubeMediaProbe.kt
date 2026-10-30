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
    const val PROOF_PREFS = "youtube_media_proof_v1"
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
        if (html.length > 1048576) return null
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
        call(Request.Builder().url(WATCH).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
            if(r.code!=200) return Discovery(null,"YT_HTTP")
            if(!r.header("Content-Type").orEmpty().startsWith("text/html",true)) return Discovery(null,"YT_FORMAT")
            val input=r.body?.byteStream() ?: return Discovery(null,"YT_FORMAT")
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
            while(out.size()<1048576) {
                if(Thread.currentThread().isInterrupted) return Discovery(null,"CANCELLED")
                val n=input.read(buf,0,minOf(buf.size,1048576-out.size()))
                if(n<0) break
                out.write(buf,0,n)
                if(out.size()%32768<8192) mediaUrls(out.toString("UTF-8")).takeIf {it.isNotEmpty()}?.let {return Discovery(it.first(),alternatives=it.drop(1))}
            }
            mediaUrls(out.toString("UTF-8")).takeIf {it.isNotEmpty()}?.let {Discovery(it.first(),alternatives=it.drop(1))} ?: Discovery(null,"YT_NO_DIRECT_MEDIA")
        }
    } catch(_:java.net.SocketTimeoutException) {Discovery(null,"TIMEOUT")}
      catch(_:Exception) {Discovery(null,if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")}

    /** Caller disables redirects and bounds the call by the shared probe deadline. */
    fun measure(url: String, call: (Request) -> Call): Result {
        if (!allowedUrl(url)) return Result(null)
        val start = System.nanoTime()
        return try {
            call(Request.Builder().url(url).header("Range", "bytes=0-${SAMPLE_BYTES - 1}")
                .header("Accept-Encoding", "identity").build()).execute().use { r ->
                // Signed URL expiry, consent and rate limiting are not route failures.
                if (r.code !in listOf(200, 206)) return Result(null,reason="YT_HTTP")
                if (!r.header("Content-Type").orEmpty().startsWith("video/", true)) return Result(null,reason="YT_FORMAT")
                if (!TransferProof.rangeValid(r.code,r.header("Content-Range"),SAMPLE_BYTES)) return Result(false)
                if (!r.header("Content-Encoding").isNullOrBlank() && !r.header("Content-Encoding").equals("identity",true)) return Result(null)
                val input = r.body?.byteStream() ?: return Result(null)
                val header=ByteArray(12);var headerSize=0
                while(headerSize<header.size) {
                    if(Thread.currentThread().isInterrupted) return Result(null)
                    val n=input.read(header,headerSize,header.size-headerSize)
                    if(n<=0) return Result(false)
                    headerSize+=n
                }
                if(!TransferProof.videoHeader(header,r.header("Content-Type").orEmpty())) return Result(false)
                var total = headerSize; val buf = ByteArray(16384)
                while (total < SAMPLE_BYTES) {
                    if (Thread.currentThread().isInterrupted) return Result(null)
                    val n = input.read(buf, 0, minOf(buf.size, SAMPLE_BYTES - total))
                    if (n < 0) return Result(false)
                    total += n
                }
                val ms = ((System.nanoTime() - start) / 1000000).coerceAtLeast(1)
                Result(true, (total * 8L / ms).toInt().coerceAtLeast(1))
            }
         } catch (_: java.io.InterruptedIOException) {
            Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT")
        } catch (_: Exception) {
            Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")
        }
    }

    fun check(call:(Request)->Call):Result {
        val discovery=discoverDetailed(call)
        val first=discovery.url ?: return Result(null,reason=discovery.reason)
        var last=Result(null,reason=discovery.reason)
        for(url in (listOf(first)+discovery.alternatives).distinct().take(3)) {
            if(Thread.currentThread().isInterrupted) return Result(null,reason="CANCELLED")
            last=measure(url,call)
            if(last.ok==true || last.reason in setOf("TIMEOUT","CANCELLED")) return last
        }
        return last
    }
}
