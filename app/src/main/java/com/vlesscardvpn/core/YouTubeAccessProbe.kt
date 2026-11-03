package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Request

/** Small YouTube HTTP reachability check through the caller's SOCKS/deadline.
 * No login, media download, direct fallback or playback/speed claim. */
object YouTubeAccessProbe {
    const val URL="https://www.youtube.com/generate_204"
    fun check(call:(Request)->Call):YouTubeMediaProbe.Result {
        if(Thread.currentThread().isInterrupted) return YouTubeMediaProbe.Result(null,reason="CANCELLED")
        return try {
            call(Request.Builder().url(URL).header("Cache-Control","no-cache, no-store")
                .header("User-Agent",YouTubeMediaProbe.USER_AGENT).build()).execute().use { r ->
                when {
                    r.request.url.toString()!=URL -> YouTubeMediaProbe.Result(null,reason="YT_ACCESS_REDIRECT")
                    r.code==204 -> YouTubeMediaProbe.Result(true,reason="YT_ACCESS_OK")
                    r.code==429 -> YouTubeMediaProbe.Result(null,reason="YT_RATE_LIMITED")
                    r.code in 300..399 -> YouTubeMediaProbe.Result(null,reason="YT_ACCESS_REDIRECT")
                    else -> YouTubeMediaProbe.Result(false,reason="YT_ACCESS_HTTP")
                }
            }
        } catch(_:java.io.InterruptedIOException) {YouTubeMediaProbe.Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT")}
          catch(_:Exception) {YouTubeMediaProbe.Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")}
    }
}
