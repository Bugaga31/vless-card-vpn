package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import org.junit.Assert.*
import org.junit.Test

class ServerOnlyPolicyR173Test {
    private fun server(host: String, protocol: String = "vless") = Server("fixture", protocol, host, 443, "fixture")
    @Test fun emptyOrLocalTorCannotServeAsRemoteUplink() {
        listOf("", "localhost", "127.0.0.1", "::1", "0.0.0.0").forEach {
            assertFalse(ServerOnlyPolicy.isRemoteServer(server(it, "socks")))
        }
        assertFalse(ServerOnlyPolicy.userRoute(server("127.0.0.1", "socks")))
    }
    @Test fun explicitRemoteServerCanUsePrivateOrDocumentationNetwork() {
        listOf("10.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "server.example").forEach {
            assertTrue(ServerOnlyPolicy.isRemoteServer(server(it)))
        }
    }
    @Test fun normalizationRemovesBypassButPreservesSecurityAndServerConfiguration() {
        val s = Settings(mode = Mode.BYEDPI, ruDirect = true, ruDns = true, proxyOnly = true,
            alwaysDirect = listOf("example.test"), blockStun = true, blockQuic = true)
        val n = ServerOnlyPolicy.normalize(s)
        assertEquals(Mode.AUTO, n.mode)
        assertFalse(n.ruDirect); assertFalse(n.ruDns); assertFalse(n.proxyOnly)
        assertTrue(n.alwaysDirect.isEmpty()); assertTrue(n.blockStun); assertTrue(n.blockQuic)
        assertEquals(n, ServerOnlyPolicy.normalize(n))
    }
}
