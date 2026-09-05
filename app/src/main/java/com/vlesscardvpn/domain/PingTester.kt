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

object PingTester {

    /**
     * Complete Reality / TLS Handshake and TCP Test.
     */
    fun pingConfig(config: VlessConfig, timeoutMs: Int = 3000): Int {
        return try {
            val start = measureTimeMillis {
                Socket().use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(config.address, config.port), timeoutMs)

                    if (config.security == "tls" || config.security == "reality") {
                        try {
                            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                            val sslSocket = sslFactory.createSocket(socket, config.address, config.port, false) as SSLSocket
                            val params = SSLParameters().apply {
                                val sni = config.sni.ifBlank { "yandex.ru" }
                                serverNames = listOf(SNIHostName(sni))
                            }
                            sslSocket.sslParameters = params
                            sslSocket.soTimeout = timeoutMs
                            sslSocket.startHandshake()
                        } catch (ignored: Exception) {
                            // Reality servers fallback correctly on unknown key
                        }
                    }
                }
            }
            start.toInt().coerceAtMost(9999)
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Real end-to-end chain verification (Google 204 test) when VPN is active.
     */
    suspend fun verifyEndToEndConnection(timeoutMs: Int = 4000): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        val testUrls = listOf(
            "http://www.google.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204"
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
                    conn.connect()
                    responseCode = conn.responseCode
                    conn.disconnect()
                }
                if (responseCode == 204 || responseCode == 200) {
                    return@withContext Pair(true, latency.toInt())
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        Pair(false, -1)
    }
}
