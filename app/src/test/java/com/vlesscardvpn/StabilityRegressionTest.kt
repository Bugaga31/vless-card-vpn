package com.vlesscardvpn

import com.vlesscardvpn.core.EvasionStrategies
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.util.UniversalConfigParser
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StabilityRegressionTest {
    private fun node() = VlessConfig(name = "TLS", address = "vpn.example.org", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "")

    @Test fun tlsWithoutSniUsesServerNotRandomOperatorDomain() {
        repeat(20) {
            val json = JSONObject(SingBoxManager.generateConfig(null, node(), AppSettings(enableSniRotation = true)))
            assertEquals("vpn.example.org", json.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("tls").getString("server_name"))
        }
    }
    @Test fun explicitSniSurvivesCustomOverrideAndRotation() {
        assertEquals("server.example.org", SingBoxManager.resolveEffectiveSni(node().copy(sni = "server.example.org"),
            AppSettings(customSniOverride = "other.example.org", enableSniRotation = true), null))
    }
    @Test fun stableProfileKeepsRealityAndCertificateVerification() {
        val node = node().copy(security = "reality", sni = "server.example.org", flow = "xtls-rprx-vision",
            publicKey = "server-key", shortId = "ab", fingerprint = "firefox")
        val proxy = JSONObject(SingBoxManager.generateConfig(null, node,
            AppSettings(evasionStrategy = "stable_tls", enableFragmentation = true)))
            .getJSONArray("outbounds").getJSONObject(0)
        val tls = proxy.getJSONObject("tls")
        assertFalse(tls.getBoolean("insecure"))
        assertFalse(tls.has("fragment"))
        assertEquals("server.example.org", tls.getString("server_name"))
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
        assertEquals("server-key", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("xtls-rprx-vision", proxy.getString("flow"))
    }
    @Test fun stableProfileHasNoExperimentalFragmentation() {
        assertNull(EvasionStrategies.effectiveFragmentParams("stable_tls", "tlshello", "5-15ms"))
        assertEquals("chrome", EvasionStrategies.activeFingerprint("stable_tls", "tlshello"))
    }
    @Test fun base64DecoderSupportsStandardAndUrlSafeInput() {
        assertArrayEquals("hello".toByteArray(), UniversalConfigParser.decodeBase64Safe("aGVsbG8="))
        assertArrayEquals(byteArrayOf(-5, -1), UniversalConfigParser.decodeBase64Safe("-_8"))
    }
    @Test fun invalidBase64DoesNotCrash() {
        assertNull(UniversalConfigParser.decodeBase64Safe("%%%not-base64%%%"))
        assertNull(UniversalConfigParser.decodeBase64Safe(""))
    }
    @Test fun base64RejectsMalformedPaddingAndMixedAlphabets() {
        assertNull(UniversalConfigParser.decodeBase64Safe("YQ="))
        assertNull(UniversalConfigParser.decodeBase64Safe("+_8="))
        assertArrayEquals("a".toByteArray(), UniversalConfigParser.decodeBase64Safe("YQ=="))
    }
    @Test fun boundedFeedAcceptsSmallAndEmptyBodies() {
        "vless://sample".toResponseBody().use {
            assertEquals("vless://sample", PublicConfigFetcher.readBoundedBody(it))
        }
        assertEquals("", PublicConfigFetcher.readBoundedBody(null))
    }
    @Test fun oversizedFeedIsRejectedBeforeParsing() {
        "a".repeat(2 * 1024 * 1024 + 1).toResponseBody().use { body ->
            assertThrows(IllegalArgumentException::class.java) { PublicConfigFetcher.readBoundedBody(body) }
        }
    }
}
