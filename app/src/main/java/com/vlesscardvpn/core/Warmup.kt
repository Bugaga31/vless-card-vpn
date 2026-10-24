package com.vlesscardvpn.core

/**
 * Warmup: after the tunnel comes up, quietly open and close the first TLS handshake
 * to the domains the user is about to need (YouTube, Discord media, Telegram).
 * The provider's DNS cache, our own resolver cache and TCP/TLS session resumption
 * make the *first* real request noticeably faster — the video starts sooner,
 * the Discord voice gateway connects faster. Traffic cost: ~1–2 KB total, one-shot.
 */
object Warmup {
    /** Domains warmed through the tunnel (browser-side TLS via SOCKS; UDP stays untouched). */
    val VIDEO = listOf(
        "www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com",
        "youtubei.googleapis.com", "play.google.com",
    )
    val SOCIAL = listOf(
        "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net",
        "web.telegram.org", "venus.web.telegram.org",
    )
    val GENERAL = listOf("www.google.com", "www.gstatic.com", "fonts.gstatic.com")

    /**
     * Domains worth warming for the current settings. Pure: no network calls —
     * the VPN service performs the actual warm-up.
     */
    fun plan(settings: com.vlesscardvpn.model.Settings): List<String> {
        val list = mutableListOf<String>()
        list += VIDEO
        val social = setOf("discord", "telegram", "instagram")
        if (settings.services.isEmpty() || settings.services.intersect(social).isNotEmpty()) list += SOCIAL
        list += GENERAL
        return list.distinct().filter { d -> d.split(".").size >= 2 && d.length <= 253 }.take(14)
    }

    /**
     * One-shot TLS warm-up through the SOCKS tunnel: DNS + handshake, then close. ~1–2 KB per domain.
     * Parallel (4 sockets at once): all first screens (YouTube, Discord, Telegram) are warm within
     * one handshake time, not after a long queue. Domains are already ordered most-wanted first.
     */
    suspend fun warm(port: Int, domains: List<String>): Unit = kotlinx.coroutines.withContext<Unit>(kotlinx.coroutines.Dispatchers.IO) {
        val sem = kotlinx.coroutines.sync.Semaphore(4)
        domains.map { d -> launch { sem.withPermit { warmOne(port, d) } } }.forEach { it.join() }
    }

    private fun kotlinx.coroutines.CoroutineScope.warmOne(port: Int, d: String) {
        try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            val s = java.net.Socket(proxy)
            try {
                s.soTimeout = 4000
                s.connect(java.net.InetSocketAddress.createUnresolved(d, 443), 4000)
                val f = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                val tls = f.createSocket(s, d, 443, true) as javax.net.ssl.SSLSocket
                tls.startHandshake()
                tls.close()
            } finally {
                s.close()
            }
        } catch (t: Throwable) {
        }
    }
}
