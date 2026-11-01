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
                val verdict = YouTubeAccessResponsePolicy.evaluate(r.code, r.request.url.toString() == URL)
                YouTubeMediaProbe.Result(verdict.ok, reason = verdict.reason)
            }
        } catch(_:java.io.InterruptedIOException) {YouTubeMediaProbe.Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT")}
          catch(_:Exception) {YouTubeMediaProbe.Result(null,reason=if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")}
    }
}
