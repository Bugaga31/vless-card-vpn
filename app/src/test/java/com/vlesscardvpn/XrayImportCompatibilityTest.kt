package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.util.UniversalConfigParser
import com.vlesscardvpn.util.XrayConfigImporter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XrayImportCompatibilityTest {
    private fun json(protocol: String = "vless", network: String = "tcp", security: String = "tls"): JSONObject =
        JSONObject().put("outbounds", JSONArray().put(JSONObject().put("protocol", protocol).put("tag", "Fixture")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", "vpn.fixture.invalid")
                .put("port", 443).put("users", JSONArray().put(JSONObject().put("id", "00000000-0000-4000-8000-000000000001"))))))
            .put("streamSettings", JSONObject().put("security", security).put("network", network))))

    @Test fun absentVlessFlowAndSniAreNotInvented() {
        val node = XrayConfigImporter.importFromJson(json().toString()).configs.single()
        assertEquals("", node.flow)
        assertEquals("vpn.fixture.invalid", node.sni)
        assertTrue(AutoConnectPolicy.supports(node))
    }
    @Test fun unknownTransportCannotSilentlyBecomeTcp() {
        val node = XrayConfigImporter.importFromJson(json(network = "xhttp").toString()).configs.single()
        assertEquals("xhttp", node.transport)
        assertFalse(AutoConnectPolicy.supports(node))
    }
    @Test fun vmessGrpcKeepsItsTransportAndServiceName() {
        val root = json(protocol = "vmess", network = "grpc")
        root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
            .put("grpcSettings", JSONObject().put("serviceName", "fixture-service"))
        val node = XrayConfigImporter.importFromJson(root.toString()).configs.single()
        assertEquals("grpc", node.transport)
        assertEquals("fixture-service", node.serviceName)
        assertEquals("vpn.fixture.invalid", node.sni)
    }
    @Test fun jsonAndBase64JsonReachTheActualImportPath() {
        val text = json().toString()
        assertEquals(1, UniversalConfigParser.parseAny(text).size)
        val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        assertEquals(1, UniversalConfigParser.parseAny(encoded).size)
    }
    @Test fun deepOrMalformedJsonIsRejectedWithoutEchoingInput() {
        val deep = "{\"data\":" + "[".repeat(80) + "0" + "]".repeat(80) + "}"
        assertTrue(UniversalConfigParser.parseAny(deep).isEmpty())
        val broken = XrayConfigImporter.importFromJson("{\"private-fixture-sentinel\":")
        assertTrue(broken.configs.isEmpty())
        assertFalse(broken.warnings.joinToString().contains("private-fixture-sentinel"))
        val root = json().put("remark", "Braces inside string: {[{}]} and \"quote\"")
        assertEquals(1, UniversalConfigParser.parseAny(root.toString()).size)
    }
    @Test fun explicitRealityParametersArePreserved() {
        val root = json(security = "reality")
        val out = root.getJSONArray("outbounds").getJSONObject(0)
        out.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0)
            .put("flow", "xtls-rprx-vision")
        out.getJSONObject("streamSettings").put("realitySettings", JSONObject().put("serverName", "peer.fixture.invalid")
            .put("fingerprint", "firefox").put("publicKey", "fixture-key").put("shortId", "abcd"))
        val node = UniversalConfigParser.parseAny(root.toString()).single()
        assertEquals("peer.fixture.invalid", node.sni); assertEquals("xtls-rprx-vision", node.flow)
        assertEquals("firefox", node.fingerprint); assertEquals("fixture-key", node.publicKey); assertEquals("abcd", node.shortId)
    }
    @Test fun missingRealitySniIsNotFabricated() {
        val node = UniversalConfigParser.parseAny(json(security = "reality").toString()).single()
        assertEquals("", node.sni); assertFalse(AutoConnectPolicy.supports(node))
    }
    @Test fun httpTransportKeepsTheProvidedHostAndPath() {
        for (protocol in listOf("vless", "vmess", "trojan")) {
            val root = json(protocol = protocol, network = "h2")
            val out = root.getJSONArray("outbounds").getJSONObject(0)
            if (protocol == "trojan") out.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", "vpn.fixture.invalid").put("port", 443).put("password", "fixture-password"))))
            out.getJSONObject("streamSettings").put("httpSettings",
                JSONObject().put("host", JSONArray().put("cdn.fixture.invalid")).put("path", "/fixture/h2"))
            val node = UniversalConfigParser.parseAny(root.toString()).single()
            assertEquals("cdn.fixture.invalid", node.wsHost); assertEquals("/fixture/h2", node.wsPath)
            assertEquals("h2", node.transport)
            val transport = outbound(node).getJSONObject("transport")
            assertEquals("cdn.fixture.invalid", transport.getJSONArray("host").getString(0))
            assertEquals("/fixture/h2", transport.getString("path"))
        }
    }

    private fun outbound(node: VlessConfig): JSONObject = JSONObject(SingBoxManager.generateConfig(null, node,
        AppSettings(evasionStrategy = "stable_tls", enableFragmentation = false))).getJSONArray("outbounds").getJSONObject(0)
    @Test fun emptyGrpcNameIsNotReplacedWithAnInventedService() {
        for (protocol in listOf("vless", "vmess", "trojan")) {
            val node = UniversalConfigParser.parseAny(json().toString()).single().copy(protocolType = protocol, transport = "grpc", serviceName = "")
            assertEquals("", outbound(node).getJSONObject("transport").getString("service_name"))
        }
    }
    @Test fun trojanWsKeepsHeadersAndPathInTheGeneratedCoreConfig() {
        val node = UniversalConfigParser.parseAny(json().toString()).single().copy(protocolType = "trojan", uuid = "fixture-password",
            transport = "ws", wsHost = "cdn.fixture.invalid", wsPath = "/fixture/ws")
        val transport = outbound(node).getJSONObject("transport")
        assertEquals("ws", transport.getString("type")); assertEquals("/fixture/ws", transport.getString("path"))
        assertEquals("cdn.fixture.invalid", transport.getJSONObject("headers").getString("Host"))
    }
    @Test fun unsupportedTransportIsRejectedEvenForManualGeneration() {
        val node = UniversalConfigParser.parseAny(json(network = "xhttp").toString()).single()
        assertTrue(runCatching { outbound(node) }.isFailure)
    }
    @Test fun unsupportedVlessEncryptionIsNotSilentlyDowngraded() {
        val root = json()
        root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            .getJSONArray("users").getJSONObject(0).put("encryption", "unsupported-encryption-fixture")
        assertTrue(UniversalConfigParser.parseAny(root.toString()).isEmpty())
        assertNull(UniversalConfigParser.parseSingleUri("vless://00000000-0000-4000-8000-000000000001@vpn.fixture.invalid:443?security=tls&encryption=unsupported"))
    }

}
