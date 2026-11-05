package com.vlesscardvpn.core

/**
 * 1.2.136: one missed 3.5 s light probe is not yet a lost route. A slow mobile link (five round trips for a fresh
 * VLESS+TLS request) or a short outage of the phone's own network used to be reported as «связь пропала»: the
 * server was marked dead, Auto hopped to another one, and the user saw endless slow reconnects.
 *
 * Before any recovery the watchdog now asks two independent questions at once:
 *  - does the running route give ANY HTTP answer to one of two URLs within a calmer [CONFIRM_MS] window;
 *  - does the phone's own network reach the internet at all (direct TCP to anycast resolvers and to the VLESS
 *    endpoints themselves — the app is outside its own VPN, so this never travels through the tunnel).
 * Only «network works, route silent» is a dead route. «Network silent» is an outage: wait, blame no server.
 */
object RouteLiveness {
    enum class Verdict { ALIVE, OFFLINE, DEAD }

    const val CONFIRM_MS = 6000L
    const val PHYSICAL_MS = 2000
    const val MAX_SERVER_TARGETS = 3
    const val GSTATIC = "https://www.gstatic.com/generate_204"
    const val CLOUDFLARE = "https://cp.cloudflare.com/generate_204"

    /** Anycast resolvers reachable from Russian and foreign networks alike; a TCP handshake only, no payload. */
    val PHYSICAL_TARGETS: List<Pair<String, Int>> = listOf("77.88.8.8" to 53, "77.88.8.1" to 53, "8.8.8.8" to 443, "1.1.1.1" to 443)

    /** TCP-carried protocols: a direct handshake to their endpoint proves the physical path. UDP ones cannot. */
    val TCP_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks")

    fun verdict(alive: Boolean, physical: Boolean): Verdict = when {
        alive -> Verdict.ALIVE
        !physical -> Verdict.OFFLINE
        else -> Verdict.DEAD
    }

    /** Any HTTP status proves that the route carries traffic both ways; only a transport failure counts as silence. */
    fun answered(code: Int): Boolean = code in 100..599

    /** The configured check URL first, then one independent public 204 endpoint; HTTPS only, two at most. */
    fun urls(testUrl: String): List<String> =
        listOf(testUrl.trim(), GSTATIC, CLOUDFLARE).filter { it.startsWith("https://") }.distinct().take(2)

    /** Fixed anycast targets plus a few TCP VLESS endpoints (so a closed lab network still has a physical witness). */
    fun targets(endpoints: List<Pair<String, Int>>): List<Pair<String, Int>> =
        (PHYSICAL_TARGETS + endpoints.filter { it.first.isNotBlank() && it.second in 1..65535 }.distinct().take(MAX_SERVER_TARGETS)).distinct()
}
