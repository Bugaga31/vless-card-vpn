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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val DEFAULT_PUBLIC_SOURCES = listOf(
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vmess.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/trojan.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/ss.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/configs/vless_reality.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/all_configs.txt",
        "https://raw.githubusercontent.com/sakazxc1400-creator/free-vpn-sub/main/sources.txt"
    )

    suspend fun fetchAndFilterWorkingConfigs(
        sources: List<String> = DEFAULT_PUBLIC_SOURCES,
        maxWorkingCount: Int = 100,
        onProgress: (scanned: Int, working: Int, currentSource: String) -> Unit = { _, _, _ -> }
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        val allParsedConfigs = mutableListOf<VlessConfig>()

        for (sourceUrl in sources) {
            try {
                val feedName = sourceUrl.substringAfterLast("/")
                onProgress(allParsedConfigs.size, 0, "Loading $feedName...")
                val request = Request.Builder().url(sourceUrl).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    // Handle nested subscription links list
                    if (sourceUrl.endsWith("sources.txt")) {
                        body.lines().filter { it.trim().startsWith("http") }.take(5).forEach { subUrl ->
                            try {
                                val subReq = Request.Builder().url(subUrl.trim()).build()
                                val subResp = client.newCall(subReq).execute()
                                if (subResp.isSuccessful) {
                                    val subBody = subResp.body?.string() ?: ""
                                    allParsedConfigs.addAll(UniversalConfigParser.parseAny(subBody))
                                }
                            } catch (_: Exception) {}
                        }
                    } else {
                        val parsed = UniversalConfigParser.parseAny(body)
                        allParsedConfigs.addAll(parsed)
                    }
                }
            } catch (e: Exception) {
                // Ignore single source errors
            }
        }

        // Deduplicate
        val uniqueConfigs = allParsedConfigs
            .distinctBy { "${it.address}:${it.port}" }
            .take(300)

        var testedCount = 0
        val workingConfigs = mutableListOf<VlessConfig>()

        // Concurrently ping configs in batches of 25
        val chunks = uniqueConfigs.chunked(25)
        for (chunk in chunks) {
            val deferredList = chunk.map { cfg ->
                async {
                    val ping = PingTester.pingConfig(cfg)
                    Pair(cfg, ping)
                }
            }

            val results = deferredList.awaitAll()
            for ((cfg, ping) in results) {
                testedCount++
                if (ping in 1..2500) {
                    val alive = cfg.copy(
                        pingMs = ping,
                        isFree = true,
                        name = if (cfg.name.contains("Node", ignoreCase = true) || cfg.name.isBlank()) {
                            "⚡ ${cfg.protocolType.uppercase()} • ${cfg.address.take(16)}"
                        } else cfg.name
                    )
                    workingConfigs.add(alive)
                }
                onProgress(testedCount, workingConfigs.size, "Testing latency...")
            }

            if (workingConfigs.size >= maxWorkingCount) break
        }

        workingConfigs.sortedBy { it.pingMs }
    }
}
