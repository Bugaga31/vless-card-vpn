package com.vlesscardvpn.data

import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.util.UniversalConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PublicConfigFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val DEFAULT_PUBLIC_SOURCES = listOf(
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/ru-sni/vless_ru.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/configs/vless_reality.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt",
        "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/26.txt",
        "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vmess.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/trojan.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/ss.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/vless",
        "https://raw.githubusercontent.com/Mosifree/-FREE2CONFIG/refs/heads/main/Reality",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/all_configs.txt",
        "https://raw.githubusercontent.com/sakazxc1400-creator/free-vpn-sub/main/sources.txt"
    )

    suspend fun fetchAndFilterWorkingConfigs(
        sources: List<String> = DEFAULT_PUBLIC_SOURCES,
        maxWorkingCount: Int = 150,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        val parsed = mutableListOf<VlessConfig>()
        for (sourceUrl in sources) {
            try {
                onProgress(parsed.size, 0, "Loading ${sourceUrl.substringAfterLast('/')}...")
                client.newCall(Request.Builder().url(sourceUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string().orEmpty()
                    if (sourceUrl.endsWith("sources.txt")) {
                        body.lines().map { it.trim() }.filter { it.startsWith("https://") }.take(6).forEach { nestedUrl ->
                            try {
                                client.newCall(Request.Builder().url(nestedUrl).build()).execute().use { nested ->
                                    if (nested.isSuccessful) parsed += UniversalConfigParser.parseAny(nested.body?.string().orEmpty())
                                }
                            } catch (_: Exception) {}
                        }
                    } else parsed += UniversalConfigParser.parseAny(body)
                }
            } catch (_: Exception) {}
        }

        val unique = parsed.distinctBy {
            "${it.protocolType}|${it.address}|${it.port}|${it.uuid}|${it.security}|${it.sni}|${it.publicKey}|${it.shortId}"
        }.take(500)
        var tested = 0
        val working = mutableListOf<VlessConfig>()
        for (chunk in unique.chunked(24)) {
            chunk.map { cfg -> async { cfg to PingTester.pingConfig(cfg) } }.awaitAll().forEach { (cfg, ping) ->
                tested++
                if (ping in 1..2500) {
                    working += cfg.copy(
                        pingMs = ping,
                        isFree = true,
                        name = if (cfg.name.contains("Node", true) || cfg.name.isBlank()) "⚡ ${cfg.protocolType.uppercase()} • ${cfg.address.take(16)}" else cfg.name
                    )
                }
                onProgress(tested, working.size, "Testing reachability...")
            }
            if (working.size >= maxWorkingCount) break
        }
        working.sortedBy { it.pingMs }.take(maxWorkingCount)
    }
}
