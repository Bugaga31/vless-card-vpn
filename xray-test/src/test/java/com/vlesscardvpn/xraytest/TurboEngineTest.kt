package com.vlesscardvpn.xraytest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TurboEngineTest {
    private fun node(host: String, port: Int = 443) =
        Node(host, port, "11111111-1111-4111-8111-111111111111", mapOf("security" to "tls"))

    @Test fun fastestOrdersByLatencyThenDead() {
        val probes = listOf(
            TurboEngine.Probe(0, 300),
            TurboEngine.Probe(1, -1),
            TurboEngine.Probe(2, 50),
        )
        assertEquals(listOf(2, 0, 1), TurboEngine.order(3, probes, TurboEngine.Strategy.FASTEST))
    }

    @Test fun stableKeepsOriginalOrder() {
        val probes = listOf(TurboEngine.Probe(0, 300), TurboEngine.Probe(1, 50))
        assertEquals(listOf(0, 1), TurboEngine.order(2, probes, TurboEngine.Strategy.STABLE))
    }

    @Test fun roundRobinRotatesAfterLastUsed() {
        val probes = emptyList<TurboEngine.Probe>()
        assertEquals(listOf(2, 0, 1), TurboEngine.order(3, probes, TurboEngine.Strategy.ROUND_ROBIN, lastUsed = 1))
        assertEquals(listOf(0, 1, 2), TurboEngine.order(3, probes, TurboEngine.Strategy.ROUND_ROBIN, lastUsed = 2))
        assertEquals(listOf(0, 1, 2), TurboEngine.order(3, probes, TurboEngine.Strategy.ROUND_ROBIN, lastUsed = -1))
    }

    @Test fun emptyInputYieldsEmptyOrder() {
        assertEquals(emptyList<Int>(), TurboEngine.order(0, emptyList(), TurboEngine.Strategy.FASTEST))
        assertEquals(emptyList<Int>(), TurboEngine.order(0, emptyList(), TurboEngine.Strategy.ROUND_ROBIN, lastUsed = 0))
    }

    @Test fun rankMeasuresLatencyAndFailures() = runTest {
        val nodes = listOf(node("fast.example"), node("dead.example"), node("slow.example"))
        val connector = TurboEngine.Connector { host, _, _ ->
            when (host) {
                "fast.example" -> 10L
                "slow.example" -> 900L
                else -> throw java.net.ConnectException("refused")
            }
        }
        val probes = TurboEngine.rank(nodes, timeoutMs = 100, connector = connector)
        assertEquals(3, probes.size)
        assertEquals(10L, probes[0].latencyMs)
        assertEquals(-1L, probes[1].latencyMs)
        assertEquals(900L, probes[2].latencyMs)
        val ordered = TurboEngine.order(3, probes, TurboEngine.Strategy.FASTEST)
        assertEquals(listOf(0, 2, 1), ordered)
    }
}
