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
        "https://raw.githubusercontent.com/GoldCaviar/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/configs/vless_reality.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/all_configs.txt"
    )

    suspend fun fetchAndFilterWorkingConfigs(
        sources: List<String> = DEFAULT_PUBLIC_SOURCES,
        maxWorkingCount: Int = 30,
        onProgress: (scanned: Int, working: Int, currentSource: String) -> Unit = { _, _, _ -> }
    ): List<VlessConfig> = withContext(Dispatchers.IO) {
        val allParsedConfigs = mutableListOf<VlessConfig>()

        for (sourceUrl in sources) {
            try {
                onProgress(allParsedConfigs.size, 0, sourceUrl)
                val request = Request.Builder().url(sourceUrl).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val parsed = UniversalConfigParser.parseAny(body)
                    allParsedConfigs.addAll(parsed)
                }
            } catch (e: Exception) {
                // Ignore failed sources
            }
        }

        // Deduplicate by IP:Port or UUID
        val uniqueConfigs = allParsedConfigs
            .distinctBy { "${it.address}:${it.port}" }
            .take(150) // limit testing pool to 150 for speed

        var testedCount = 0
        val workingConfigs = mutableListOf<VlessConfig>()

        // Parallel ping test with concurrency limit
        val chunks = uniqueConfigs.chunked(15)
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
                    val alive = cfg.copy(pingMs = ping, isFree = true)
                    workingConfigs.add(alive)
                }
                onProgress(testedCount, workingConfigs.size, "Testing latency...")
            }

            if (workingConfigs.size >= maxWorkingCount) break
        }

        // Sort by ping ascending (best first)
        workingConfigs.sortedBy { it.pingMs }
    }
}
