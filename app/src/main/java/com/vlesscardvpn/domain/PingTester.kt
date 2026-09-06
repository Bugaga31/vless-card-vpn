package com.vlesscardvpn.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
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
    fun testDetailedLatency(config: VlessConfig, timeoutMs: Int = 3500): LatencyBreakdown {
        if (config.address.isBlank() || config.port <= 0) {
            return LatencyBreakdown(errorReason = "Invalid server address or port: ${config.address}:${config.port}")
        }
        var tcpLatency = -1
        var tlsLatency = -1
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.tcpNoDelay = true
                val tcpStart = System.currentTimeMillis()
                socket.connect(InetSocketAddress(config.address.trim(), config.port), timeoutMs)
                tcpLatency = (System.currentTimeMillis() - tcpStart).toInt().coerceAtLeast(1)

                // Generic TLS cannot authenticate Reality. Report TCP reachability here;
                // authenticated tunnel health is verified by verifyEndToEndConnection().
                if (config.security.equals("tls", true)) {
                    val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, config.address.trim(), config.port, false) as SSLSocket
                    sslSocket.sslParameters = SSLParameters().apply {
                        serverNames = listOf(SNIHostName(config.sni.ifBlank { config.address }.trim()))
                    }
                    sslSocket.soTimeout = timeoutMs
                    val tlsStart = System.currentTimeMillis()
                    try {
                        sslSocket.startHandshake()
                        tlsLatency = (System.currentTimeMillis() - tlsStart).toInt().coerceAtLeast(1)
                    } catch (e: Exception) {
                        return LatencyBreakdown(tcpLatency, -1, -1, false, "TLS handshake failed: ${e.localizedMessage ?: "Unknown SSL error"}")
                    }
                }
            }
            LatencyBreakdown(tcpLatency, tlsLatency, -1, tcpLatency > 0)
        } catch (e: Exception) {
            LatencyBreakdown(errorReason = e.localizedMessage ?: "Connection timed out")
        }
    }

    fun pingConfig(config: VlessConfig, timeoutMs: Int = 3000): Int {
        val result = testDetailedLatency(config, timeoutMs)
        return if (result.success) if (result.tlsMs > 0) result.tlsMs else result.tcpMs else -1
    }

    suspend fun verifyEndToEndConnection(timeoutMs: Int = 3500): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        for (endpoint in endpoints) {
            try {
                var connection: HttpsURLConnection? = null
                var stream: InputStream? = null
                try {
                    var code = -1
                    val latency = measureTimeMillis {
                        connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
                            connectTimeout = timeoutMs
                            readTimeout = timeoutMs
                            instanceFollowRedirects = false
                            useCaches = false
                            setRequestProperty("User-Agent", "VLESS-Card-Connectivity/1.0")
                            setRequestProperty("Connection", "close")
                        }
                        connection?.connect()
                        code = connection?.responseCode ?: -1
                        if (code == 204) stream = connection?.inputStream
                    }
                    if (code == 204) return@withContext true to latency.toInt().coerceAtLeast(1)
                } finally {
                    try { stream?.close() } catch (_: Exception) {}
                    try { connection?.disconnect() } catch (_: Exception) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        false to -1
    }
}
