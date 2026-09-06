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

/** Current Android route, not a per-outbound core URLTest and not a throughput test. */
object ServiceReachability {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun checkBoth(): List<ServiceReachabilityResult> = withTimeout(15000L) {
        coroutineScope {
            ServiceTarget.values().map { target -> async {
                val samples = mutableListOf<ServiceProbe>()
                repeat(3) { index ->
                    samples += probe(target)
                    if (index < 2) delay(150L)
                }
                ServiceReachabilityResult(target, samples)
            } }.awaitAll()
        }
    }

    private suspend fun probe(target: ServiceTarget): ServiceProbe = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(target.url)
            .header("Cache-Control", "no-cache, no-store")
            .header("User-Agent", "VlessCard-ServiceCheck/1.0")
            .apply { if (target == ServiceTarget.TELEGRAM) head() else get() }
            .build()
        val call = client.newCall(request)
        val started = System.nanoTime()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(ServiceProbe(error = "Таймаут или сетевая ошибка"))
            }
            override fun onResponse(call: Call, response: Response) {
                val sample = response.use {
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    ServiceProbe(latencyMs = elapsed, httpCode = it.code,
                        error = if (it.code == target.expectedCode) null else "Неожиданный HTTP ${it.code}")
                }
                if (continuation.isActive) continuation.resume(sample)
            }
        })
    }
}
