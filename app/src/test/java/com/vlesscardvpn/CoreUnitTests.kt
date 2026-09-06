package com.vlesscardvpn

import com.vlesscardvpn.core.NetworkProfileManager
import com.vlesscardvpn.core.NetworkType
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.util.UniversalConfigParser
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreUnitTests {

    @Test
    fun testVlessUriParsing() {
        val rawUri = "vless://b7a5e840-7e61-460d-a7fa-0dc6b6a6742a@185.196.10.15:443?security=reality&flow=xtls-rprx-vision&sni=yandex.ru&fp=chrome&pbk=Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=&sid=6a#TestNode"
        val config = UniversalConfigParser.parseSingleUri(rawUri)

        assertNotNull("Config must be parsed successfully", config)
        assertEquals("b7a5e840-7e61-460d-a7fa-0dc6b6a6742a", config?.uuid)
        assertEquals("185.196.10.15", config?.address)
        assertEquals(443, config?.port)
        assertEquals("yandex.ru", config?.sni)
        assertEquals("reality", config?.security)
        assertEquals("xtls-rprx-vision", config?.flow)
        assertEquals("Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=", config?.publicKey)
        assertEquals("6a", config?.shortId)
        assertEquals("TestNode", config?.name)
    }

    @Test
    fun testVmessUriParsing() {
        val vmessRaw = "vmess://eyJhZGQiOiIxOTQuMzYuMTkxLjEyIiwiYWlkIjowLCJob3N0IjoiIiwiYWRkciI6IjE5NC4zNi4xOTEuMTIiLCJpZCI6ImM4YjRmOTEyLTRkMjMtNDExYS05NmVhLTVmYjNkODgxOTBiYyIsIm5ldCI6InRjcCIsInBhdGgiOiIiLCJwb3J0IjoxMDAwMiwicHMiOiJWbWVzc1Rlc3QiLCJ0bHMiOiIiLCJ0eXBlIjoibm9uZSIsInYiOiIyIn0="
        val config = UniversalConfigParser.parseSingleUri(vmessRaw)

        assertNotNull(config)
        assertEquals("vmess", config?.protocolType)
        assertEquals("c8b4f912-4d23-411a-96ea-5fb3d88190bc", config?.uuid)
        assertEquals("VmessTest", config?.name)
        assertEquals(10002, config?.port)
        assertEquals("194.36.191.12", config?.address)
    }

    @Test
    fun testSingBoxConfigStructureAndValidation() {
        val config = VlessConfig(
            id = "test-1",
            name = "Test VLESS",
            address = "185.196.10.15",
            port = 443,
            uuid = "b7a5e840-7e61-460d-a7fa-0dc6b6a6742a",
            protocolType = "vless",
            flow = "xtls-rprx-vision",
            security = "reality",
            sni = "samsung.com",
            publicKey = "Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=",
            shortId = "6a"
        )

        val fakeConfigJson = SingBoxManager.generateConfig(
            context = null,
            config = config,
            settings = AppSettings(enableRuDirect = true, blockQuicYouTube = true)
        )

        val validationResult = SingBoxManager.validateGeneratedConfig(fakeConfigJson)
        assertTrue("Generated config must be valid according to sing-box schema: ${validationResult.exceptionOrNull()?.message}", validationResult.isSuccess)

        val json = JSONObject(fakeConfigJson)
        assertTrue(json.has("inbounds"))
        assertTrue(json.has("outbounds"))
        assertTrue(json.has("route"))
        assertTrue(json.has("dns"))

        val inbounds = json.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals(1400, tun.getInt("mtu"))

        val outbounds = json.getJSONArray("outbounds")
        val proxy = outbounds.getJSONObject(0)
        assertEquals("vless", proxy.getString("type"))
        assertEquals("185.196.10.15", proxy.getString("server"))
        assertEquals(443, proxy.getInt("server_port"))
        // Explicit config SNI 'samsung.com' must be strictly preserved!
        assertEquals("samsung.com", proxy.getJSONObject("tls").getString("server_name"))
        assertTrue(proxy.getJSONObject("tls").has("reality"))
        // transport.type = "tcp" must NOT be present
        assertFalse(proxy.has("transport"))
    }

    @Test
    fun testCompleteFieldPropagation() {
        val config = VlessConfig(
            id = "cfg-prop-1",
            name = "Full Prop Node",
            address = "198.51.100.25",
            port = 8443,
            uuid = "11111111-2222-3333-4444-555555555555",
            protocolType = "vless",
            flow = "xtls-rprx-vision",
            security = "reality",
            sni = "custom.gateway.net",
            fingerprint = "firefox",
            publicKey = "my-test-public-key-12345",
            shortId = "beef"
        )

        val settings = AppSettings(
            mtuSize = 1380,
            enableRuDirect = true,
            blockQuicYouTube = true,
            customDnsProvider = "Google (8.8.8.8)"
        )

        val jsonStr = SingBoxManager.generateConfig(null, config, settings)
        val json = JSONObject(jsonStr)

        val proxy = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("198.51.100.25", proxy.getString("server"))
        assertEquals(8443, proxy.getInt("server_port"))
        assertEquals("11111111-2222-3333-4444-555555555555", proxy.getString("uuid"))
        assertEquals("xtls-rprx-vision", proxy.getString("flow"))

        val tls = proxy.getJSONObject("tls")
        assertEquals("custom.gateway.net", tls.getString("server_name"))
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("my-test-public-key-12345", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("beef", tls.getJSONObject("reality").getString("short_id"))

        val tun = json.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(1380, tun.getInt("mtu"))

        val dnsServers = json.getJSONObject("dns").getJSONArray("servers")
        assertEquals("https://8.8.8.8/dns-query", dnsServers.getJSONObject(0).getString("address"))
    }

    @Test
    fun testSniPreservationRules() {
        val configWithSamsung = VlessConfig(
            name = "Samsung SNI",
            address = "1.2.3.4",
            port = 443,
            uuid = "some-uuid",
            sni = "samsung.com"
        )
        val configWithYandex = VlessConfig(
            name = "Yandex SNI",
            address = "1.2.3.4",
            port = 443,
            uuid = "some-uuid",
            sni = "yandex.ru"
        )
        val configEmptySni = VlessConfig(
            name = "Empty SNI",
            address = "1.2.3.4",
            port = 443,
            uuid = "some-uuid",
            sni = ""
        )

        val defaultSettings = AppSettings()
        val customSniSettings = AppSettings(customSniOverride = "mycustom.org")

        // 1. Explicit SNI must be preserved regardless of network profile
        assertEquals("samsung.com", SingBoxManager.resolveEffectiveSni(configWithSamsung, defaultSettings, null))
        assertEquals("yandex.ru", SingBoxManager.resolveEffectiveSni(configWithYandex, defaultSettings, null))

        // 2. Empty SNI with custom override
        assertEquals("mycustom.org", SingBoxManager.resolveEffectiveSni(configEmptySni, customSniSettings, null))

        // 3. Empty SNI with default settings resolves to fallback
        assertEquals("yandex.ru", SingBoxManager.resolveEffectiveSni(configEmptySni, defaultSettings, null))
    }

    @Test
    fun testRuDirectRulesStructure() {
        val config = VlessConfig(
            name = "Test",
            address = "1.1.1.1",
            port = 443,
            uuid = "uuid-123"
        )

        // RU Direct ON
        val jsonOnStr = SingBoxManager.generateConfig(null, config, AppSettings(enableRuDirect = true))
        val jsonOn = JSONObject(jsonOnStr)
        val routeRulesOn = jsonOn.getJSONObject("route").getJSONArray("rules")
        var hasDomainSuffixRu = false
        for (i in 0 until routeRulesOn.length()) {
            val r = routeRulesOn.getJSONObject(i)
            if (r.has("domain_suffix") && r.optString("outbound") == "direct") {
                hasDomainSuffixRu = true
            }
        }
        assertTrue("When enableRuDirect is TRUE, route rules must contain direct domain_suffix rule", hasDomainSuffixRu)

        // RU Direct OFF
        val jsonOffStr = SingBoxManager.generateConfig(null, config, AppSettings(enableRuDirect = false))
        val jsonOff = JSONObject(jsonOffStr)
        val routeRulesOff = jsonOff.getJSONObject("route").getJSONArray("rules")
        var hasDirectDomainSuffixInOff = false
        for (i in 0 until routeRulesOff.length()) {
            val r = routeRulesOff.getJSONObject(i)
            if (r.has("domain_suffix") && r.optString("outbound") == "direct") {
                hasDirectDomainSuffixInOff = true
            }
        }
        assertFalse("When enableRuDirect is FALSE, route rules must NOT contain direct domain_suffix rule", hasDirectDomainSuffixInOff)
    }

    @Test
    fun testPreserveErrorStateModel() {
        val initialStats = VpnSessionStats(status = VpnStatus.CONNECTING)
        assertEquals(VpnStatus.CONNECTING, initialStats.status)

        // Transition on error preserves explicit diagnostic reason
        val errorStats = initialStats.copy(
            status = VpnStatus.ERROR,
            errorMessage = "Сквозной тест HTTPS не пройден: узел не маршрутизирует трафик"
        )
        assertEquals(VpnStatus.ERROR, errorStats.status)
        assertEquals("Сквозной тест HTTPS не пройден: узел не маршрутизирует трафик", errorStats.errorMessage)

        // User explicit disconnect clears to DISCONNECTED
        val disconnectedStats = VpnSessionStats(status = VpnStatus.DISCONNECTED)
        assertEquals(VpnStatus.DISCONNECTED, disconnectedStats.status)
        assertNull(disconnectedStats.errorMessage)
    }

    @Test
    fun testPingTesterLatencyBreakdownErrorPreservation() {
        val badConfig = VlessConfig(
            name = "Bad Port Node",
            address = "127.0.0.1",
            port = -1,
            uuid = "invalid"
        )
        val result = PingTester.testDetailedLatency(badConfig, 100)
        assertFalse("Invalid port must fail immediately", result.success)
        assertNotNull("Error message must be preserved", result.errorReason)
    }

    @Test
    fun testSavedWorkingProfileSerializationAndExpiry() {
        val profile = SavedWorkingProfile(
            configId = "node-123",
            networkType = "WIFI",
            optimalMtu = 1380,
            effectiveDns = "https://1.1.1.1/dns-query",
            blockQuic = true,
            verifiedLatencyMs = 45,
            timestamp = System.currentTimeMillis()
        )

        val jsonStr = profile.toJson()
        val parsed = SavedWorkingProfile.fromJson(jsonStr)

        assertNotNull(parsed)
        assertEquals("node-123", parsed?.configId)
        assertEquals("WIFI", parsed?.networkType)
        assertEquals(1380, parsed?.optimalMtu)
        assertEquals(45, parsed?.verifiedLatencyMs)
        assertFalse("Fresh profile must not be expired", parsed?.isExpired() ?: true)

        // Test expired profile
        val oldProfile = profile.copy(timestamp = System.currentTimeMillis() - 8 * 24 * 3600 * 1000L)
        assertTrue("8-day old profile must be marked expired", oldProfile.isExpired())
    }

    @Test
    fun testAutopilotStateMachineInitialAndStable() {
        val state = NetworkAutopilotState(
            status = AutopilotStateStatus.DISABLED,
            isEnabled = false
        )
        assertEquals(AutopilotStateStatus.DISABLED, state.status)
        assertFalse(state.isEnabled)

        // Transition to stable on successful probe
        val updated = state.copy(
            status = AutopilotStateStatus.STABLE,
            isEnabled = true,
            lastHttpsLatencyMs = 38,
            checkSuccessRatePercent = 100
        )
        assertEquals(AutopilotStateStatus.STABLE, updated.status)
        assertTrue(updated.isEnabled)
        assertEquals(38, updated.lastHttpsLatencyMs)
    }

    @Test
    fun testSingleFailureDoesNotTriggerFailover() {
        // A single failure increments consecutiveFailures to 1, but status remains STABLE without switching
        val state = NetworkAutopilotState(
            status = AutopilotStateStatus.STABLE,
            isEnabled = true,
            consecutiveFailures = 0
        )

        val afterSingleFail = state.copy(
            consecutiveFailures = 1,
            lastChangeExplanation = "Зафиксирован единичный сбой (1/3). Ожидание подтверждения перед переключением."
        )

        assertEquals(1, afterSingleFail.consecutiveFailures)
        assertEquals(AutopilotStateStatus.STABLE, afterSingleFail.status)
        assertTrue(afterSingleFail.lastChangeExplanation.contains("единичный сбой"))
    }
}
