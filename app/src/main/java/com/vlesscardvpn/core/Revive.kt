package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/**
 * «Оживить мёртвые серверы»: many free servers sit behind Cloudflare (ws / xhttp / httpupgrade / grpc + TLS).
 * When the operator blocks the IP they resolve to, the same server still answers on any other Cloudflare edge
 * address — the edge routes by the name (SNI / Host), not by the IP. So for a dead CDN server we try copies whose
 * address is a different Cloudflare IP (random ones from the public ranges + a few well-known names) and keep the
 * copy that passes. Same idea as the «clean IP» scanners, done automatically.
 */
object Revive {
    /** Cloudflare's published IPv4 ranges (cloudflare.com/ips-v4). */
    val CF_RANGES = listOf("173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
        "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15",
        "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22")
    /** Ranges where any edge serves any site (the big anycast blocks) — random addresses are taken from here. */
    private val SCAN = listOf("104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "188.114.96.0/20", "162.159.0.0/16")
    /** Names on Cloudflare that operators rarely block (resolved on the phone to the edge nearest to it). */
    val CLEAN_NAMES = listOf("www.visa.com", "creativecommons.org", "www.speedtest.net", "cdnjs.cloudflare.com", "discord.com", "icook.hk")
    private val CDN_NETS = setOf("ws", "xhttp", "httpupgrade", "grpc", "splithttp")

    private fun ipv4(s: String): Long? {
        val p = s.split('.'); if (p.size != 4) return null
        var v = 0L; for (x in p) { val n = x.toIntOrNull() ?: return null; if (n !in 0..255) return null; v = v * 256 + n }
        return v
    }
    fun inRange(ip: String, cidr: String): Boolean {
        val a = ipv4(ip) ?: return false
        val (net, bits) = cidr.split('/').let { (ipv4(it[0]) ?: return false) to (it[1].toIntOrNull() ?: return false) }
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (a and mask) == (net and mask)
    }
    fun isCloudflare(ip: String) = CF_RANGES.any { inRange(ip, it) }
    fun isIp(s: String) = ipv4(s) != null || ':' in s

    /** The name Cloudflare routes by: SNI, else Host, else the address itself when it is a name. */
    fun routeName(s: Server): String = s.sni.ifEmpty { s.host }.ifEmpty { if (isIp(s.address)) "" else s.address }

    /** A server that could live behind a CDN: V2Ray protocols over ws/xhttp/httpupgrade/grpc, with a name to route by. */
    fun cdnLike(s: Server): Boolean = s.protocol in setOf("vless", "vmess", "trojan") && s.network in CDN_NETS &&
        s.security != "reality" && routeName(s).isNotEmpty()

    /** Edge addresses that already revived something (newest first): tried before random ones next time. */
    val good = java.util.concurrent.CopyOnWriteArrayList<String>()
    fun remember(addrs: Collection<String>) { val l = (addrs + good).distinct().take(12); good.clear(); good.addAll(l) }
    /** What to try: up to 4 proven addresses, 2 clean names, the rest random — [n] in total. */
    fun pick(n: Int = 8, rnd: kotlin.random.Random = kotlin.random.Random): List<String> {
        val known = good.take(4)
        val names = CLEAN_NAMES.filter { it !in known }.shuffled(rnd).take(2)
        return (known + names + randomIps(n, rnd)).distinct().take(n)
    }

    fun randomIps(n: Int, rnd: kotlin.random.Random = kotlin.random.Random): List<String> = List(n) {
        val c = SCAN[rnd.nextInt(SCAN.size)]
        val base = ipv4(c.substringBefore('/'))!!; val bits = c.substringAfter('/').toInt()
        val v = base + rnd.nextLong(1, (1L shl (32 - bits)) - 1)
        "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}"
    }.distinct()

    /** Copies of [s] on other edge addresses; SNI and Host stay the real name, so TLS and routing still match. */
    fun variants(s: Server, addresses: List<String>): List<Server> {
        val name = routeName(s)
        return addresses.filter { !it.equals(s.address, true) }.map { a ->
            s.copy(address = a, sni = if (s.security == "tls") s.sni.ifEmpty { name } else s.sni, host = s.host.ifEmpty { name },
                name = s.name.substringBefore(" · CDN") + " · CDN " + a)
        }
    }

    /** Resolves the server name on the phone: does it point into Cloudflare (so edge swapping can work)? */
    fun behindCloudflare(s: Server): Boolean = runCatching {
        val a = if (isIp(s.address)) listOf(s.address) else java.net.InetAddress.getAllByName(s.address).mapNotNull { it.hostAddress }
        a.any { isCloudflare(it) } || !isIp(s.address) && a.isEmpty()
    }.getOrDefault(!isIp(s.address)) // the name doesn't resolve (DNS blocked) — worth a try anyway
}
