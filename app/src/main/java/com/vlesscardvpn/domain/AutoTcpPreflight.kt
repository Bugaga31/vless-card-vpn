package com.vlesscardvpn.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Optional TCP hints have their own deadline. Unknown/failed hints never exclude a route. */
object AutoTcpPreflight {
    const val BUDGET_MS = 1600L
    const val SOCKET_TIMEOUT_MS = 1200
    const val PARALLELISM = 8

    suspend fun measure(
        candidates: List<VlessConfig>,
        probe: suspend (VlessConfig) -> Int = { tcpHint(it) }
    ): List<Pair<VlessConfig, Int>> {
        val bounded = candidates.take(AutoSearchPolicy.MAX_CANDIDATES)
        val results = ConcurrentHashMap<Int, Int>()
        val permits = Semaphore(PARALLELISM)
        withTimeoutOrNull(BUDGET_MS) {
            coroutineScope {
                bounded.mapIndexed { index, config -> async {
                    permits.withPermit {
                        val hint = probe(config)
                        results[index] = if (hint > 0) hint else -1
                    }
                } }.awaitAll()
            }
        }
        currentCoroutineContext().ensureActive()
        return bounded.mapIndexed { index, config -> config to (results[index] ?: -1) }
    }

    internal suspend fun tcpHint(
        config: VlessConfig,
        timeoutMs: Int = SOCKET_TIMEOUT_MS,
        connect: (Socket, VlessConfig, Int) -> Unit = { socket, node, timeout ->
            socket.connect(InetSocketAddress(node.address.trim(), node.port), timeout)
        }
    ): Int {
        require(timeoutMs > 0)
        return withTimeoutOrNull(timeoutMs.toLong()) {
            suspendCancellableCoroutine { continuation ->
                val socket = Socket()
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                // Do not create a blocking child coroutine: Android's DNS resolution can
                // outlive cancellation. The caller can return on deadline; the socket closes.
                // The OS resolver itself is not cancellable through java.net.Socket.
                Dispatchers.IO.dispatch(continuation.context, Runnable {
                    if (!continuation.isActive) { socket.close(); return@Runnable }
                    val started = System.nanoTime()
                    val result = try {
                        socket.use {
                            connect(socket, config, timeoutMs)
                            ((System.nanoTime() - started) / 1_000_000).coerceIn(1, 60000).toInt()
                        }
                    } catch (_: Exception) { -1 }
                    if (continuation.isActive) continuation.resume(result)
                })
            }
        } ?: -1
    }
}
