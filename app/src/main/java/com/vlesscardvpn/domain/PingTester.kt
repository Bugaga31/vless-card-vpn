package com.vlesscardvpn.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
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
     * Measure complete TCP, TLS and Handshake latency without silently ignoring TLS errors.
     */
    fun testDetailedLatency(config: VlessConfig, timeoutMs: Int = 3500): LatencyBreakdown {
        var tcpLatency = -1
        var tlsLatency = -1
        var errorMessage: String? = null

        try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                val tcpStart = System.currentTimeMillis()
                socket.connect(InetSocketAddress(config.address, config.port), timeoutMs)
                tcpLatency = (System.currentTimeMillis() - tcpStart).toInt().coerceAtLeast(1)

                if (config.security.equals("tls", ignoreCase = true) || config.security.equals("reality", ignoreCase = true)) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sslSocket = sslFactory.createSocket(socket, config.address, config.port, false) as SSLSocket
                    val params = SSLParameters().apply {
                        val sni = config.sni.ifBlank { "yandex.ru" }
                        serverNames = listOf(SNIHostName(sni))
                    }
                    sslSocket.sslParameters = params
                    sslSocket.soTimeout = timeoutMs
                    val tlsStart = System.currentTimeMillis()
                    try {
                        sslSocket.startHandshake()
                        tlsLatency = (System.currentTimeMillis() - tlsStart).toInt().coerceAtLeast(1)
                    } catch (e: Exception) {
                        // For Reality servers, handshake with standard CA certs will naturally fail verification,
                        // but receiving TLS server hello confirms port and TLS stack are alive!
                        if (config.security.equals("reality", ignoreCase = true) && (e.message?.contains("CertPathValidatorException") == true || e.message?.contains("Trust anchor") == true || e.message?.contains("handshake") == true)) {
                            tlsLatency = (System.currentTimeMillis() - tlsStart).toInt().coerceAtLeast(1)
                        } else {
                            errorMessage = "TLS handshake failed: ${e.localizedMessage}"
                            return LatencyBreakdown(tcpMs = tcpLatency, tlsMs = -1, success = false, errorReason = errorMessage)
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
                errorReason = e.localizedMessage ?: "TCP Connection timed out"
            )
        }
    }

    /**
     * Backward-compatible simple ping calculation.
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
     * Real end-to-end verification through the active TUN / Sing-Box tunnel.
     */
    suspend fun verifyEndToEndConnection(timeoutMs: Int = 4000): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val testUrls = listOf(
            "http://www.google.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://www.youtube.com/generate_204"
        )
        for (testUrl in testUrls) {
            try {
                var responseCode = -1
                val latency = measureTimeMillis {
                    val url = URL(testUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.instanceFollowRedirects = false
                    conn.useCaches = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    conn.connect()
                    responseCode = conn.responseCode
                    conn.disconnect()
                }
                if (responseCode in 200..204 || responseCode == 301 || responseCode == 302) {
                    return@withContext Pair(true, latency.toInt().coerceAtLeast(1))
                }
            } catch (e: Exception) {
                // Continue trying fallbacks
            }
        }
        Pair(false, -1)
    }
}
