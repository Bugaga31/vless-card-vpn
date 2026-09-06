package com.vlesscardvpn.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

data class LatencyBreakdown(
    val tcpMs: Int = -1,
    val tlsMs: Int = -1,
    val httpMs: Int = -1,
    val success: Boolean = false,
    val errorReason: String? = null
)

object PingTester {

    /**
     * Endpoint reachability test for server nodes (TCP connect + TLS client hello check).
     * Used for server list ping measurements.
     * Does NOT substitute authenticated VPN tunnel verification.
     */
    fun testDetailedLatency(config: VlessConfig, timeoutMs: Int = 3500): LatencyBreakdown {
        var tcpLatency = -1
        var tlsLatency = -1

        if (config.address.isBlank() || config.port <= 0) {
            return LatencyBreakdown(
                success = false,
                errorReason = "Invalid server address or port: ${config.address}:${config.port}"
            )
        }

        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                val tcpStart = System.currentTimeMillis()
                socket.connect(InetSocketAddress(config.address.trim(), config.port), timeoutMs)
                tcpLatency = (System.currentTimeMillis() - tcpStart).toInt().coerceAtLeast(1)

                val isTls = config.security.equals("tls", ignoreCase = true)
                val isReality = config.security.equals("reality", ignoreCase = true)

                if (isTls || isReality) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSocket = sslFactory.createSocket(socket, config.address.trim(), config.port, false) as SSLSocket
                    val effectiveSni = config.sni.ifBlank { "yandex.ru" }.trim()
                    val params = SSLParameters().apply {
                        serverNames = listOf(SNIHostName(effectiveSni))
                    }
                    sslSocket.sslParameters = params
                    sslSocket.soTimeout = timeoutMs
                    val tlsStart = System.currentTimeMillis()
                    try {
                        sslSocket.startHandshake()
                        tlsLatency = (System.currentTimeMillis() - tlsStart).toInt().coerceAtLeast(1)
                    } catch (e: Exception) {
                        // For Reality servers, standard CA validation is expected to fail on untrusted target cert,
                        // but receiving TLS Alert or ServerHello confirms TLS port responsiveness.
                        val msg = e.message ?: ""
                        if (isReality && (msg.contains("CertPathValidatorException") || msg.contains("Trust anchor") || msg.contains("handshake"))) {
                            tlsLatency = (System.currentTimeMillis() - tlsStart).toInt().coerceAtLeast(1)
                        } else {
                            return LatencyBreakdown(
                                tcpMs = tcpLatency,
                                tlsMs = -1,
                                success = false,
                                errorReason = "TLS handshake failed: ${e.localizedMessage ?: "Unknown SSL error"}"
                            )
                        }
                    }
                }
            }
            return LatencyBreakdown(
                tcpMs = tcpLatency,
                tlsMs = tlsLatency,
                httpMs = -1,
                success = tcpLatency > 0,
                errorReason = null
            )
        } catch (e: Exception) {
            return LatencyBreakdown(
                tcpMs = -1,
                tlsMs = -1,
                httpMs = -1,
                success = false,
                errorReason = e.localizedMessage ?: "Connection timed out"
            )
        }
    }

    /**
     * Simple ping calculation for UI lists.
     */
    fun pingConfig(config: VlessConfig, timeoutMs: Int = 3000): Int {
        val result = testDetailedLatency(config, timeoutMs)
        return if (result.success) {
            if (result.tlsMs > 0) result.tlsMs else result.tcpMs
        } else {
            -1
        }
    }

    /**
     * Strict end-to-end verification through the active TUN / Sing-Box proxy tunnel.
     * - Uses HTTPS endpoints only (no plaintext HTTP).
     * - Strictly rejects 301/302 redirects (captive portals or ISP blocks).
     * - Requires HTTP 204 No Content response.
     * - Supports coroutine cancellation and bounded timeouts.
     */
    suspend fun verifyEndToEndConnection(timeoutMs: Int = 3500): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val secureCheckEndpoints = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )

        for (endpoint in secureCheckEndpoints) {
            try {
                var conn: HttpsURLConnection? = null
                var stream: InputStream? = null
                try {
                    val url = URL(endpoint)
                    var responseCode = -1
                    val latency = measureTimeMillis {
                        conn = (url.openConnection() as HttpsURLConnection).apply {
                            connectTimeout = timeoutMs
                            readTimeout = timeoutMs
                            instanceFollowRedirects = false // Do not accept redirects as valid proof!
                            useCaches = false
                            defaultUseCaches = false
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:122.0) Gecko/122.0")
                            setRequestProperty("Connection", "close")
                        }
                        conn?.connect()
                        responseCode = conn?.responseCode ?: -1
                        stream = conn?.inputStream
                    }

                    // Only HTTP 204 indicates an unintercepted connectivity probe
                    if (responseCode == 204) {
                        return@withContext Pair(true, latency.toInt().coerceAtLeast(1))
                    }
                } finally {
                    try { stream?.close() } catch (_: Exception) {}
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Try next verification endpoint
            }
        }
        Pair(false, -1)
    }
}
