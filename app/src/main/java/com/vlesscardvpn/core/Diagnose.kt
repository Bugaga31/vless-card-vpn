package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * «Почему не работает?» — the cause, not just "fail" (ideas from the open projects RIPDPI and tspu-probe):
 * - IP: TCP to the server doesn't connect at all → masks can't help, only another server / CDN / WARP;
 * - SNI: TLS with the server's own name hangs, but the same IP answers TLS for an allowed name (ya.ru) →
 *   the name is filtered: fragmenting / SNI masks help;
 * - TLS: TCP connects but no TLS goes through at all → the IP is throttled for TLS;
 * - freeze (inside the tunnel): a download stops after ~16 KB — the mobile TSPU «заморозка» of connections to
 *   hosting IPs; helps another hosting / a server behind a CDN.
 */
object Diagnose {
    enum class Cause(val title: String, val advice: String) {
        OK("доступен", ""),
        DNS("имя сервера не находится (DNS)", "включите «DNS через туннель» или укажите сервер по IP"),
        IP("заблокирован по IP — соединение не устанавливается", "маски тут не помогут: нужен другой сервер, сервер за CDN или WARP"),
        SNI("фильтруется по имени сайта (SNI)", "помогут маски с дроблением и SNI разрешённого сайта — запустите подбор маскировки"),
        TLS("TCP проходит, но TLS к этому IP режется", "попробуйте маски с шумом/дроблением или другой сервер"),
    }

    enum class Hs { OK, ANSWER, TIMEOUT, RESET, FAIL }

    /** Pure: TCP connected?, TLS with the real name, TLS with an allowed name. */
    fun classify(dnsOk: Boolean, tcp: Boolean, real: Hs?, benign: Hs?): Cause = when {
        !dnsOk -> Cause.DNS
        !tcp -> Cause.IP
        real == null -> Cause.OK                               // not a TLS server: TCP is all we can say
        real == Hs.OK || real == Hs.ANSWER -> Cause.OK
        benign == Hs.OK || benign == Hs.ANSWER -> Cause.SNI
        else -> Cause.TLS
    }

    private fun tcp(addr: InetSocketAddress): Boolean = (1..2).any {
        runCatching { Socket().use { s -> s.connect(addr, 4000) }; true }.getOrDefault(false)
    }

    private val trustAll by lazy {
        javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }), null)
        }.socketFactory
    }

    /** One TLS handshake to [addr] presenting [sni]; certificates are not checked (only "does TLS get through"). */
    fun handshake(addr: InetSocketAddress, sni: String): Hs = try {
        Socket().use { raw ->
            raw.connect(addr, 4000); raw.soTimeout = 5000
            (trustAll.createSocket(raw, sni, addr.port, false) as javax.net.ssl.SSLSocket).use { s ->
                s.sslParameters = s.sslParameters.apply { serverNames = listOf(javax.net.ssl.SNIHostName(sni)) }
                s.startHandshake(); Hs.OK
            }
        }
    } catch (e: java.net.SocketTimeoutException) { Hs.TIMEOUT }
    catch (e: javax.net.ssl.SSLHandshakeException) { if ((e.message ?: "").contains("reset", true) || (e.message ?: "").contains("closed", true)) Hs.RESET else Hs.ANSWER }
    catch (e: java.net.SocketException) { if ((e.message ?: "").contains("reset", true)) Hs.RESET else Hs.FAIL }
    catch (e: Exception) { Hs.FAIL }

    data class ServerCause(val server: Server, val cause: Cause)

    /** Direct (the app is outside the VPN) check of one server. */
    fun server(s: Server): Cause {
        val ip = runCatching { java.net.InetAddress.getByName(s.address) }.getOrNull() ?: return Cause.DNS
        val addr = InetSocketAddress(ip, s.port)
        val udp = s.protocol == "wireguard" || s.protocol == "hysteria2" || s.network == "kcp"
        if (udp) return Cause.OK // UDP: no connect to test without the protocol itself
        if (!tcp(addr)) return Cause.IP
        if (s.security != "tls" && s.security != "reality") return Cause.OK
        val name = s.sni.ifEmpty { s.host.ifEmpty { s.address } }
        val real = handshake(addr, name)
        val benign = if (real == Hs.OK || real == Hs.ANSWER) null else handshake(addr, "ya.ru")
        return classify(true, true, real, benign)
    }

    suspend fun servers(list: List<Server>): List<ServerCause> = withContext(Dispatchers.IO) {
        coroutineScope { list.take(6).map { s -> async { ServerCause(s, runCatching { server(s) }.getOrDefault(Cause.TLS)) } }.awaitAll() }
    }

    enum class Freeze(val title: String) { OK("заморозки нет"), FROZEN("загрузка замирает после ~16 КБ — это «заморозка» ТСПУ"), FAIL("загрузка не пошла вообще"), }

    /** Pure: what a download that got [bytes] of [want] and then stopped (timedOut) means. */
    fun freezeOf(bytes: Long, want: Long, timedOut: Boolean): Freeze = when {
        bytes >= want -> Freeze.OK
        timedOut && bytes in 4_000L..64_000L -> Freeze.FROZEN
        bytes > 64_000L -> Freeze.OK
        else -> Freeze.FAIL
    }

    /** Through the running tunnel (SOCKS [port]): 256 KB from Cloudflare, 7 s without new bytes = stopped. */
    suspend fun freeze(port: Int): Freeze = withContext(Dispatchers.IO) {
        val want = 256_000L; var got = 0L; var timedOut = false
        runCatching {
            val c = java.net.URL("https://speed.cloudflare.com/__down?bytes=$want").openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 7000
            try { c.inputStream.use { src -> val buf = ByteArray(8192); while (true) { val n = src.read(buf); if (n < 0) break; got += n } } }
            catch (e: java.net.SocketTimeoutException) { timedOut = true } finally { c.disconnect() }
        }
        freezeOf(got, want, timedOut)
    }

    /**
     * Networks (AS numbers) of hostings where the TSPU interferes — the crowdsourced list of the open project
     * Viktor45/as-tspu (MIT). A server there is likely to get the «заморозка» or slow-downs on mobile.
     */
    val TSPU_ASN: Set<Int> = "174,1219,1273,6142,8075,8560,8849,9009,10929,12876,13213,13335,13727,14061,14544,16276,16509,16625,20054,20473,20860,20940,21100,21130,21859,24940,24961,25198,25369,25788,26383,27458,29447,29838,29873,30058,30083,31898,32149,32181,32810,32934,33993,35042,36352,36530,40021,40676,42065,42708,42831,44907,48014,48282,48753,49453,49981,51167,51430,51765,53667,53755,54113,54253,54600,55170,56630,56971,58061,58065,59930,60068,62014,62041,62240,62563,63018,63023,63150,63473,63593,63949,135682,136744,137409,138915,140443,141995,151151,151338,153366,197540,199524,200019,200325,202053,202422,202662,202675,203020,204957,209058,209265,209312,209703,209753,209765,209847,209920,211157,211301,211812,211829,211865,212238,212317,213230,213887,213900,214041,214172,214206,215540,215730,215939,216071,263702,263812,272575,274113,274115,274116,274117,274118,274119,393511,393515,393929,394093,394177,396051,396356,396982,397571,398343,399622".split(',').map { it.toInt() }.toSet()

    /** "AS24940 Hetzner Online GmbH" → 24940 and the name; null when unknown. */
    fun parseOrg(org: String): Pair<Int, String>? = Regex("^AS(\\d+)\\s*(.*)$").find(org.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2].trim() }

    private fun org(ip: String): Pair<Int, String>? = runCatching {
        val c = java.net.URL("https://ipinfo.io/$ip/org").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        try { parseOrg(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }.getOrNull()

    suspend fun report(selected: List<Server>, socksPort: Int?): String {
        val sb = StringBuilder()
        val wl = Whitelist.check()
        sb.append("Сеть: ").append(wl.verdict.title.replaceFirstChar { it.lowercase() }).append('.')
        if (selected.isNotEmpty()) {
            sb.append("\n\nСерверы (напрямую, мимо VPN):")
            val causes = servers(selected)
            val orgs = withContext(Dispatchers.IO) { coroutineScope { causes.map { (s, _) -> async { runCatching { java.net.InetAddress.getByName(s.address).hostAddress }.getOrNull()?.let { org(it) } } }.awaitAll() } }
            causes.forEachIndexed { i, (s, c) ->
                sb.append("\n• ").append(s.name.take(30)).append(" — ").append(c.title).append(if (c.advice.isNotEmpty()) ": " + c.advice else "")
                orgs[i]?.let { (n, name) -> if (n in TSPU_ASN) sb.append(" ⚠ Хостинг AS$n ${name.take(24)} — в списке сетей, где ТСПУ мешает (возможны заморозка и тормоза на мобильном).") }
            }
        }
        if (socksPort != null) {
            val f = freeze(socksPort)
            sb.append("\n\nВнутри VPN: ").append(f.title).append('.')
            if (f == Freeze.FROZEN) sb.append(" Помогает сервер у другого хостинга или за CDN (Cloudflare / Яндекс Облако); маски тут почти бессильны.")
        }
        return sb.toString()
    }
}
