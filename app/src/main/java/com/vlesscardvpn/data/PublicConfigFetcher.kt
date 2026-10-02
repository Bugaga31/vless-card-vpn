package com.vlesscardvpn.data

import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.AutoConnectPolicy
import com.vlesscardvpn.domain.SubscriptionPolicy
import com.vlesscardvpn.domain.TunnelHealthChecker
import okhttp3.Credentials
import okhttp3.Authenticator
import okhttp3.Route
import java.net.Proxy
import java.net.InetSocketAddress
import okhttp3.Dns
import java.net.UnknownHostException
import java.net.InetAddress
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.util.UniversalConfigParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import okhttp3.ResponseBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PublicConfigFetcher {
    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !SubscriptionPolicy.publicAddress(it) })
                throw UnknownHostException("Непубличный адрес источника")
            }
        })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .followRedirects(true)
        .build()

    private const val MAX_FEED_BYTES = 2L * 1024 * 1024
    private const val MAX_CANDIDATES = 500

    internal fun readBoundedBody(body: ResponseBody?): String {
        if (body == null) return ""
        require(body.contentLength() <= MAX_FEED_BYTES) { "Подписка слишком большая" }
        val source = body.source()
        require(!source.request(MAX_FEED_BYTES + 1)) { "Подписка слишком большая" }
        return source.readUtf8()
    }

    // Community sources: availability and privacy are not independently certified.
    val DEFAULT_PUBLIC_SOURCES = listOf(
        // 1. High Priority: Curated Russia Reality & White-list sources (Fastest & Active)
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/ru-sni/vless_ru.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
        "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/configs/vless_reality.txt",
        "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/refs/heads/main/githubmirror/26.txt",
        // 2. Secondary Curated Reality pools
        "https://raw.githubusercontent.com/Mosifree/-FREE2CONFIG/refs/heads/main/Reality",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
        "https://raw.githubusercontent.com/Danialsamadi/v2go/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt"
    )

    /** Public feeds are untrusted candidates, never "working VPNs" merely because a port opens. */
    suspend fun fetchCandidates(
        sources: List<String> = DEFAULT_PUBLIC_SOURCES,
        maxSources: Int = 12,
        onProgress: (String) -> Unit = {}
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        require(maxSources in 1..12)
        val parsed = mutableListOf<VlessConfig>()
        for (group in sources.mapNotNull(SubscriptionPolicy::url).distinct().take(maxSources).chunked(4)) {
            coroutineContext.ensureActive()
            val results = coroutineScope {
                group.map { url -> async {
                    val body = fetchBody(url) ?: return@async emptyList<VlessConfig>()
                    UniversalConfigParser.parseAny(body).filter(AutoConnectPolicy::supports).take(60)
                        .map { it.copy(isFree = true, healthState = "UNKNOWN", source = "public", pingMs = -1) }
                } }.awaitAll()
            }
            // Round-robin sources instead of letting the first large feed fill the pool.
            repeat(60) { index -> results.forEach { nodes -> nodes.getOrNull(index)?.let(parsed::add) } }
            onProgress("Получено ${parsed.size} кандидатов; работа туннеля ещё не проверена")
            if (parsed.size >= MAX_CANDIDATES) break
        }
        parsed.distinctBy(AutoConnectPolicy::identity).take(MAX_CANDIDATES)
    }

    internal suspend fun fetchBody(url: String): String? = suspendCancellableCoroutine { continuation ->
        // The application UID is excluded from VPN routing. Explicit authenticated HTTP
        // CONNECT on the same loopback inbound keeps updates on the active encrypted route.
        val active = TunnelHealthChecker.activeProxy
        val http = if (active == null) client else client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", active.port)))
            .proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    val address = route?.proxy?.address() as? InetSocketAddress ?: return null
                    if (address.port != active.port || address.hostString != "127.0.0.1" ||
                        response.request.header("Proxy-Authorization") != null) return null
                    return response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(active.username, active.password)).build()
                }
            }).build()
        // No automatic direct fallback if that route fails or closes.
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = try { response.use { if (it.isSuccessful) readBoundedBody(it.body) else null } }
                    catch (_: Exception) { null }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }

    suspend fun importSubscription(url: String): List<VlessConfig> {
        val safe = SubscriptionPolicy.url(url) ?: error("Нужна публичная HTTPS-подписка без логина в адресе")
        val body = fetchBody(safe) ?: error("Подписка недоступна или слишком большая")
        return UniversalConfigParser.parseAny(body).take(500).also { require(it.isNotEmpty()) { "Подписка не содержит поддерживаемых конфигураций" } }
    }

    suspend fun fetchAndFilterWorkingConfigs(
        sources: List<String> = DEFAULT_PUBLIC_SOURCES,
        maxWorkingCount: Int = 150,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        require(maxWorkingCount in 1..150)
        val candidates = fetchCandidates(sources) { onProgress(0, 0, it) }
        var tested = 0
        val reachable = mutableListOf<VlessConfig>()
        for (chunk in candidates.chunked(8)) {
            coroutineContext.ensureActive()
            coroutineScope {
                chunk.map { cfg -> async { cfg to PingTester.pingConfig(cfg, 1500) } }.awaitAll()
            }.forEach { (cfg, ping) ->
                tested++
                if (ping > 0) reachable += cfg.copy(pingMs = ping, healthState = "UNKNOWN")
                onProgress(tested, reachable.size, "Проверяем TCP-порты, не работу VPN")
            }
            if (reachable.size >= maxWorkingCount) break
        }
        reachable.sortedBy { it.pingMs }.take(maxWorkingCount)
    }
}
