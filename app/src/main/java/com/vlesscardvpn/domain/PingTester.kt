package com.vlesscardvpn.domain

import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

object PingTester {
    // Logic inspired by common python ping tester for VLESS (TCP connect latency)
    fun pingHost(host: String, port: Int, timeoutMs: Int = 3000): Int {
        return try {
            val start = measureTimeMillis {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
            }
            start.toInt().coerceAtMost(9999)
        } catch (e: Exception) {
            -1
        }
    }

    fun pingConfig(config: VlessConfig): Int {
        return pingHost(config.address, config.port)
    }
}