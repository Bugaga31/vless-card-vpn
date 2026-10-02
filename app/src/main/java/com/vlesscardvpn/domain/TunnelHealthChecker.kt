package com.vlesscardvpn.domain

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.net.Socket
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** HTTPS response over the selected native outbound, not the app's excluded Android route. */
data class TunnelProbe(val label: String, val latencyMs: Int = -1, val httpCode: Int = -1, val expectedCode: Int = 204) {
    val passed: Boolean get() = latencyMs > 0 && httpCode == expectedCode
}
data class TunnelHealthReport(val probes: List<TunnelProbe> = emptyList(), val checkedAt: Long = 0L) {
    val internet: Boolean get() = probes.any { it.passed && it.label in setOf("Cloudflare", "YouTube · HTTPS", "Telegram · веб") }
    val youtube: Boolean get() = probes.any { it.label == "YouTube · HTTPS" && it.passed }
    val telegram: Boolean get() = probes.any { it.label == "Telegram · веб" && it.passed }
    val preferredServices: Boolean get() = internet && youtube && telegram
    val latencyMs: Int get() = probes.filter { it.passed }.minOfOrNull { it.latencyMs } ?: -1
}

object TunnelHealthChecker {
    @Volatile var activeProxy: LocalProbeProxy? = null
        internal set
    fun clear(proxy: LocalProbeProxy?) { if (activeProxy === proxy) activeProxy = null }

    suspend fun check(proxy: LocalProbeProxy? = activeProxy, timeoutMs: Int = 4000): TunnelHealthReport {
        if (proxy == null) return TunnelHealthReport()
        return coroutineScope {
            listOf(
                Triple("Cloudflare", "https://cp.cloudflare.com/generate_204", 204),
                Triple(ServiceTarget.YOUTUBE.label, ServiceTarget.YOUTUBE.url, ServiceTarget.YOUTUBE.expectedCode),
                Triple(ServiceTarget.TELEGRAM.label, ServiceTarget.TELEGRAM.url, ServiceTarget.TELEGRAM.expectedCode)
            ).map { (label, url, expected) -> async {
                val result = probe(proxy, url, expected, timeoutMs)
                NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.HTTPS_CHECK,
                    target = when (label) { "Cloudflare" -> DiagnosticTarget.INTERNET; ServiceTarget.YOUTUBE.label -> DiagnosticTarget.YOUTUBE; else -> DiagnosticTarget.TELEGRAM },
                    failure = result.failure, httpCode = result.httpCode ?: -1, latencyMs = result.latencyMs ?: -1))
                TunnelProbe(label, result.latencyMs ?: -1, result.httpCode ?: -1, expected)
            } }.awaitAll().let { TunnelHealthReport(it, System.currentTimeMillis()) }
        }
    }

    internal suspend fun probe(
        proxy: LocalProbeProxy?, url: String, expectedCode: Int, timeoutMs: Int,
        tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    ): ServiceProbe {
        if (proxy == null) return ServiceProbe(error = "Нет активного проверяемого маршрута", failure = DiagnosticFailure.NO_ROUTE)
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null)
        return try {
            withTimeout(timeoutMs.toLong()) {
                suspendCancellableCoroutine { continuation ->
                    val socket = Socket()
                    continuation.invokeOnCancellation { try { socket.close() } catch (_: Exception) {} }
                    CoroutineScope(continuation.context + Dispatchers.IO).launch {
                        val started = System.nanoTime()
                        var stage = DiagnosticFailure.CONNECT
                        val result = try {
                            socket.use {
                                stage = DiagnosticFailure.PROXY
                                Socks5Client.connect(socket, proxy, uri.host, if (uri.port > 0) uri.port else 443, timeoutMs)
                                (tlsFactory.createSocket(socket, uri.host, if (uri.port > 0) uri.port else 443, true) as SSLSocket).use { tls ->
                                    tls.soTimeout = timeoutMs
                                    tls.sslParameters = tls.sslParameters.apply {
                                        endpointIdentificationAlgorithm = "HTTPS"
                                        serverNames = listOf(SNIHostName(uri.host))
                                    }
                                    stage = DiagnosticFailure.TLS
                                    tls.startHandshake()
                                    stage = DiagnosticFailure.HTTP
                                    val path = uri.rawPath.ifBlank { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                                    val hostHeader = uri.host + if (uri.port > 0 && uri.port != 443) ":${uri.port}" else ""
                                    tls.getOutputStream().apply {
                                        write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nUser-Agent: VlessCard-RouteCheck/1.0\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                                    }
                                    // Read only the status line (bounded); never retain a page body or cookies.
                                    val input = tls.getInputStream(); val status = StringBuilder()
                                    while (status.length < 512) {
                                        val b = input.read(); check(b >= 0) { "Missing HTTP status" }
                                        if (b == 10) break
                                        if (b != 13) status.append(b.toChar())
                                    }
                                    val match = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*|)$").matchEntire(status.toString())
                                    val code = match?.groupValues?.get(1)?.toInt() ?: error("Invalid HTTP status")
                                    val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                                    ServiceProbe(elapsed, code, if (code == expectedCode) null else "Неожиданный HTTP $code",
                                        if (code == expectedCode) DiagnosticFailure.NONE else DiagnosticFailure.HTTP)
                                }
                            }
                        } catch (e: Exception) { ServiceProbe(error = "Маршрут, TLS или сервер недоступны",
                            failure = if (e is java.net.SocketTimeoutException) DiagnosticFailure.TIMEOUT else stage) }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) { ServiceProbe(error = "Таймаут маршрута", failure = DiagnosticFailure.TIMEOUT) }
    }
}
