package com.vlesscardvpn.xraytest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Turbo engine: parallel latency probing of candidates and connection strategies.
 * Pure Kotlin/JVM — unit-testable without Android.
 */
object TurboEngine {
    /** latencyMs < 0 means unreachable. */
    data class Probe(val index: Int, val latencyMs: Long)

    enum class Strategy { FASTEST, STABLE, ROUND_ROBIN }

    fun interface Connector {
        /** Returns connect latency in ms, throws on failure. */
        fun connect(host: String, port: Int, timeoutMs: Int): Long
    }

    val tcpConnector = Connector { host, port, timeoutMs ->
        val begin = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        (System.nanoTime() - begin) / 1_000_000
    }

    /** Probes all nodes in parallel; one slow node never blocks the rest. */
    suspend fun rank(nodes: List<Node>, timeoutMs: Int = 2000, connector: Connector = tcpConnector): List<Probe> = coroutineScope {
        nodes.mapIndexed { index, node ->
            async(Dispatchers.IO) {
                val latency = try { connector.connect(node.host, node.port, timeoutMs) } catch (_: Exception) { -1L }
                Probe(index, latency)
            }
        }.awaitAll()
    }

    /**
     * Returns node indices in the order the service should try them.
     * FASTEST: reachable sorted by latency, unreachable last.
     * STABLE: original order.
     * ROUND_ROBIN: rotation starting right after [lastUsed].
     */
    fun order(nodeCount: Int, probes: List<Probe>, strategy: Strategy, lastUsed: Int = -1): List<Int> {
        require(nodeCount >= 0)
        return when (strategy) {
            Strategy.FASTEST -> {
                val alive = probes.filter { it.latencyMs >= 0 }.sortedBy { it.latencyMs }.map { it.index }
                val dead = (0 until nodeCount).filter { i -> probes.none { it.index == i && it.latencyMs >= 0 } }
                alive + dead
            }
            Strategy.STABLE -> (0 until nodeCount).toList()
            Strategy.ROUND_ROBIN -> {
                if (nodeCount == 0) emptyList()
                else {
                    val start = if (lastUsed in 0 until nodeCount) (lastUsed + 1) % nodeCount else 0
                    (0 until nodeCount).map { (start + it) % nodeCount }
                }
            }
        }
    }
}
