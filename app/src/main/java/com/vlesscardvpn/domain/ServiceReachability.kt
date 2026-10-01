package com.vlesscardvpn.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class ServiceTarget(val label: String, val url: String, val expectedCode: Int) {
    TELEGRAM("Telegram · веб", "https://telegram.org/", 200),
    YOUTUBE("YouTube · HTTPS", "https://www.youtube.com/generate_204", 204)
}

data class ServiceProbe(val latencyMs: Int? = null, val httpCode: Int? = null, val error: String? = null)
data class ServiceReachabilityResult(val target: ServiceTarget, val samples: List<ServiceProbe>) {
    val successful: List<ServiceProbe> get() = samples.filter { it.error == null && it.httpCode == target.expectedCode && it.latencyMs != null }
    val medianMs: Int? get() {
        val values = successful.mapNotNull { it.latencyMs }.sorted()
        if (values.isEmpty()) return null
        return if (values.size % 2 == 1) values[values.size / 2]
        else ((values[values.size / 2 - 1].toLong() + values[values.size / 2]) / 2).toInt()
    }
    val summary: String get() = when {
        successful.size == samples.size && samples.isNotEmpty() -> "Все ${samples.size} запроса получили ожидаемый ответ"
        successful.isNotEmpty() -> "Частичный ответ: ${successful.size}/${samples.size}"
        else -> "Ожидаемый ответ не получен"
    }
}

/** Always checks the active native outbound; never falls back to the excluded app route. */
object ServiceReachability {
    suspend fun checkBoth(): List<ServiceReachabilityResult> = coroutineScope {
        ServiceTarget.values().map { target -> async {
            val samples = mutableListOf<ServiceProbe>()
            repeat(3) {
                samples += TunnelHealthChecker.probe(TunnelHealthChecker.activeProxy, target.url, target.expectedCode, 4000)
            }
            ServiceReachabilityResult(target, samples)
        } }.awaitAll()
    }
}
