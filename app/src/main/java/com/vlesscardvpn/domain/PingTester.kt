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

                // This is TCP-port reachability only, never TLS/Reality authentication.
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

    /**
     * Measures download speed by fetching a test file through the VPN tunnel.
     * Returns speed in bytes per second, or -1 on failure.
     */
    suspend fun measureDownloadSpeed(
        testUrl: String = "https://speed.cloudflare.com/__down?bytes=1048576",
        timeoutMs: Int = 8000
    ): Long = withContext(Dispatchers.IO) {
        // The application UID is excluded from Android VPN routing. A direct request
        // would measure the ISP rather than the selected outbound. Do not fabricate it.
        -1L
    }

    suspend fun verifyEndToEndConnection(timeoutMs: Int = 3000): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val report = TunnelHealthChecker.check(timeoutMs = timeoutMs)
        report.internet to report.latencyMs
    }
}
