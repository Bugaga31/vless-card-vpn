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
    data class Result(val ok: Boolean?, val kbps: Int = 0, val reason: String = "", val firstByteMs: Long = 0)

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

    internal fun mediaUrl(html: String): String? = runCatching {
        val root = JSONObject(playerJson(html) ?: return null)
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val data = root.optJSONObject("streamingData") ?: return null
        // Prefer video; an audio-only URL doesn't prove that video playback can fetch media.
        for (key in listOf("formats", "adaptiveFormats")) {
            val list = data.optJSONArray(key) ?: continue
            for (i in 0 until list.length()) {
                val f = list.optJSONObject(i) ?: continue
                val url = f.optString("url")
                if (f.optString("mimeType").startsWith("video/") && allowedUrl(url)) return url
            }
        }
        null
    }.getOrNull()

    data class Discovery(val url: String? = null, val reason: String = "player-incomplete")

    internal fun playerResource(html: String): Discovery = runCatching {
        val root = JSONObject(playerJson(html) ?: return Discovery())
        when (root.optJSONObject("playabilityStatus")?.optString("status")) {
            "LOGIN_REQUIRED" -> Discovery(reason="player-login-required")
            "OK" -> mediaUrl(html)?.let { Discovery(it,"player-url") } ?: Discovery(reason="player-no-direct-media")
            else -> Discovery(reason="player-unplayable")
        }
    }.getOrDefault(Discovery())

    fun discover(call: (Request) -> Call): String? = discoverResult(call).url

    fun discoverResult(call: (Request) -> Call): Discovery = try {
        call(Request.Builder().url(WATCH).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
            if (r.code != 200) return Discovery(reason="player-http-${r.code}")
            if (!r.header("Content-Type").orEmpty().startsWith("text/html",true)) return Discovery(reason="player-unavailable")
            val input = r.body?.byteStream() ?: return Discovery(reason="player-unavailable")
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (out.size() < 1048576) {
                if (Thread.currentThread().isInterrupted) return Discovery(reason="cancelled")
                val n = input.read(buf,0,minOf(buf.size,1048576-out.size()))
                if (n < 0) break
                out.write(buf,0,n)
                if (out.size() % 32768 < 8192) {
                    val result = playerResource(out.toString("UTF-8"))
                    // A complete challenge/unsupported player is terminal for this resource, not a dead server.
                    if (result.reason != "player-incomplete") return result
                }
            }
            playerResource(out.toString("UTF-8")).let {
                if (it.reason == "player-incomplete") Discovery(reason="player-unavailable") else it
            }
        }
    } catch (error: Exception) {
        if (com.vlesscardvpn.BuildConfig.DEBUG) runCatching { android.util.Log.i("E2E",
            "media-player failure=" + generateSequence<Throwable>(error) { it.cause }.take(4).joinToString("/") { it.javaClass.simpleName }) }
        Discovery(reason=if (Thread.currentThread().isInterrupted) "cancelled" else "player-transport")
    }

    /** Caller disables redirects and bounds the call by the shared probe deadline. */
    fun measure(url: String, call: (Request) -> Call): Result {
        if (!allowedUrl(url)) return Result(null, reason = "invalid-media-url")
        val start = System.nanoTime()
        return try {
            call(Request.Builder().url(url).header("Range", "bytes=0-${SAMPLE_BYTES - 1}")
                .header("Accept-Encoding", "identity").build()).execute().use { r ->
                // Signed URL expiry, consent and rate limiting are not route failures.
                if (r.code !in listOf(200, 206)) return Result(null, reason = "media-http-${r.code}")
                if (!r.header("Content-Type").orEmpty().startsWith("video/", true)) return Result(null, reason = "not-video")
                val input = r.body?.byteStream() ?: return Result(null, reason = "empty-body")
                var total = 0; var firstByteMs = 0L; val buf = ByteArray(16384)
                while (total < SAMPLE_BYTES) {
                    if (Thread.currentThread().isInterrupted) return Result(null)
                    val n = input.read(buf, 0, minOf(buf.size, SAMPLE_BYTES - total))
                    if (n < 0) return Result(false, reason = "early-eof", firstByteMs = firstByteMs)
                    if (total == 0 && n > 0) firstByteMs = (System.nanoTime() - start) / 1000000
                    total += n
                }
                val ms = ((System.nanoTime() - start) / 1000000).coerceAtLeast(1)
                Result(true, (total * 8L / ms).toInt().coerceAtLeast(1), "video-range", firstByteMs)
            }
        } catch (_: Exception) { Result(if (Thread.currentThread().isInterrupted) null else false, reason = if (Thread.currentThread().isInterrupted) "cancelled" else "media-transport") }
    }

    fun check(call: (Request) -> Call): Result {
        val resource = discoverResult(call)
        return resource.url?.let { measure(it,call) } ?: Result(null,reason=resource.reason)
    }
}
