package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import com.vlesscardvpn.util.UniversalConfigParser
import org.junit.Assert.*
import org.junit.Test

/** Share links from current panels that Auto previously could not use at all. */
class ShareLinkCompatibilityTest {
    private val pbk = "Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2kA"
    @Test fun xrayRawTypeIsTcpAndVisionStaysEligible() {
        val c = UniversalConfigParser.parseSingleUri(
            "vless://00000000-0000-4000-8000-000000000001@r.example.org:443?type=raw&security=reality&flow=xtls-rprx-vision&sni=www.example.com&fp=chrome&pbk=$pbk&sid=6a#raw")!!
        assertEquals("tcp", c.transport)
        assertTrue(AutoConnectPolicy.supports(c))
    }
    @Test fun httpUpgradeLinkIsParsedAndEligible() {
        val c = UniversalConfigParser.parseSingleUri(
            "vless://00000000-0000-4000-8000-000000000001@h.example.org:443?type=httpupgrade&security=tls&sni=h.example.org&host=cdn.example.org&path=%2Fup#hu")!!
        assertEquals("httpupgrade", c.transport); assertEquals("/up", c.wsPath); assertEquals("cdn.example.org", c.wsHost)
        assertTrue(AutoConnectPolicy.supports(c))
    }
    @Test fun xhttpIsKeptAsIsAndExplained() {
        val c = UniversalConfigParser.parseSingleUri(
            "vless://00000000-0000-4000-8000-000000000001@x.example.org:443?type=xhttp&security=reality&sni=www.example.com&pbk=$pbk&sid=6a#x")!!
        assertEquals("xhttp", c.transport); assertFalse(AutoConnectPolicy.supports(c))
        assertTrue(AutoConnectPolicy.skipReason(c)!!.contains("XHTTP"))
    }
    @Test fun trojanWebSocketTransportIsNoLongerDropped() {
        val c = UniversalConfigParser.parseSingleUri(
            "trojan://p%40ss@t.example.org:443?type=ws&security=tls&sni=t.example.org&host=t.example.org&path=%2Fws#tr")!!
        assertEquals("ws", c.transport); assertEquals("/ws", c.wsPath); assertEquals("p@ss", c.uuid)
    }
    @Test fun vmessTlsNoneIsPlaintextAndGrpcServiceNameIsKept() {
        val c = UniversalConfigParser.parseSingleUri("vmess://eyJ2IjogIjIiLCAicHMiOiAiVk0iLCAiYWRkIjogInZtLmV4YW1wbGUub3JnIiwgInBvcnQiOiAiNDQzIiwgImlkIjogImM4YjRmOTEyLTRkMjMtNDExYS05NmVhLTVmYjNkODgxOTBiYyIsICJuZXQiOiAiZ3JwYyIsICJwYXRoIjogInN2YyIsICJ0bHMiOiAibm9uZSIsICJob3N0IjogIiJ9")!!
        assertEquals("none", c.security); assertEquals("grpc", c.transport); assertEquals("svc", c.serviceName)
        assertEquals("vm.example.org", c.sni)
    }
}
