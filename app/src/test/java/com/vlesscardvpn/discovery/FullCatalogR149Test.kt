package com.vlesscardvpn

import com.vlesscardvpn.core.FullCatalogChecks
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test

class FullCatalogR149Test {
    private fun server(i: Int, protocol: String = "vless") = Server("route-$i", protocol, "127.0.0.1", 443, "key-$i")

    @Test fun everyConfigurationIncludingTailSurvivesAllWaves() {
        val routes = (0 until 1200).map { server(it) }
        val ordered = FullCatalogChecks.ordered(routes, routes.associate { it.id to 1 })
        val waves = FullCatalogChecks.waves(ordered)
        assertTrue(waves.all { it.size <= FullCatalogChecks.WAVE_SIZE })
        assertEquals(routes.map { it.id }, waves.flatten().map { it.id })
        assertEquals(routes.last(), waves.last().last())
    }

    @Test fun failedAndUnknownTcpAndUdpAreNotExcluded() {
        val failed = server(0); val unknown = server(1); val udp = server(2, "hysteria2"); val live = server(3)
        val all = FullCatalogChecks.ordered(listOf(failed, unknown, udp, live), mapOf(failed.id to 0, live.id to 10))
        assertEquals(live, all.first())
        assertEquals(setOf(failed, unknown, udp, live), all.toSet())
    }

    @Test fun duplicateEndpointWithDifferentCredentialsIsNotDeduplicated() {
        val routes = (0 until 161).map { server(it) }
        assertEquals(161, FullCatalogChecks.waves(FullCatalogChecks.ordered(routes + routes[0], emptyMap())).flatten().size)
    }
}
