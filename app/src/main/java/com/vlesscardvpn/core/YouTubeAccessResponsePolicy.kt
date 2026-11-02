package com.vlesscardvpn.core

/** Reachability, not playback. Service-side limitations never become a route PASS. */
object YouTubeAccessResponsePolicy {
    data class Verdict(val ok: Boolean?, val reason: String)
    fun evaluate(code: Int, sameEndpoint: Boolean): Verdict = when {
        !sameEndpoint || code in 300..399 -> Verdict(null, "YT_ACCESS_REDIRECT")
        code == 204 -> Verdict(true, "YT_ACCESS_OK")
        code == 429 -> Verdict(null, "YT_RATE_LIMITED")
        code == 403 || code == 451 -> Verdict(null, "YT_ACCESS_RESTRICTED")
        code in 500..599 -> Verdict(null, "YT_SERVICE_UNAVAILABLE")
        else -> Verdict(false, "YT_ACCESS_HTTP")
    }
}
