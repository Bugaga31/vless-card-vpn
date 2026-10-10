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

    suspend fun report(selected: List<Server>, socksPort: Int?): String {
        val sb = StringBuilder()
        val wl = Whitelist.check()
        sb.append("Сеть: ").append(wl.verdict.title.replaceFirstChar { it.lowercase() }).append('.')
        if (selected.isNotEmpty()) {
            sb.append("\n\nСерверы (напрямую, мимо VPN):")
            servers(selected).forEach { (s, c) -> sb.append("\n• ").append(s.name.take(30)).append(" — ").append(c.title).append(if (c.advice.isNotEmpty()) ": " + c.advice else "") }
        }
        if (socksPort != null) {
            val f = freeze(socksPort)
            sb.append("\n\nВнутри VPN: ").append(f.title).append('.')
            if (f == Freeze.FROZEN) sb.append(" Помогает сервер у другого хостинга или за CDN (Cloudflare / Яндекс Облако); маски тут почти бессильны.")
        }
        return sb.toString()
    }
}
