package com.vlesscardvpn

import android.content.Context
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.*
import com.vlesscardvpn.domain.*
import go.Seq
import io.nekohasekai.libbox.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Opt-in local fixture. Exercises JNI + real VLESS/TLS + authenticated probe, NOT Android TUN/DPI. */
@RunWith(AndroidJUnit4::class)
class NativeOutboundFixtureTest {
    private lateinit var context: Context
    private lateinit var certificate: File
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        val caPath = args.getString("fixture_ca_path")
        Assume.assumeTrue("Requires explicit locally controlled fixture", caPath != null)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        certificate = File(caPath!!)
        assertTrue(certificate.isFile)
        Seq.setContext(context)
        Libbox.setup(SetupOptions().apply {
            basePath = File(context.filesDir, "native-fixture").apply { mkdirs() }.absolutePath
            workingPath = basePath
            tempPath = context.cacheDir.absolutePath
            fixAndroidStack = true
        })
    }
    @Test fun compatibleVlessTlsActuallyTransfersHttps() = exercise(RouteProfile.COMPATIBLE)
    @Test fun fragmentVlessTlsActuallyTransfersHttps() = exercise(RouteProfile.FRAGMENT)
    @Test fun tcpSplitActuallyTransfersHttps() = exercise(RouteProfile.BYEDPI, ByeDpiPreset.TCP_ONLY)
    @Test fun tlsRecordSplitActuallyTransfersHttps() = exercise(RouteProfile.BYEDPI, ByeDpiPreset.TLS_RECORD_ONLY)
    @Test fun combinedSplitActuallyTransfersHttps() = exercise(RouteProfile.BYEDPI, ByeDpiPreset.COMBINED)

    @Test fun liveRealityVisionActuallyTransfersHttps() = exercise(RouteProfile.COMPATIBLE, reality = true)
    @Test fun wrongRealityKeyCannotPassRouteCheck() = exercise(RouteProfile.COMPATIBLE, reality = true, wrongKey = true, expectFailure = true)
    @Test fun wrongVlessUuidCannotPassRouteCheck() = exercise(RouteProfile.COMPATIBLE, wrongUuid = true, expectFailure = true)
    @Test fun innerHttpsHostnameMismatchIsRejected() = exercise(RouteProfile.COMPATIBLE, wrongHostname = true, expectFailure = true)

    private fun exercise(profile: RouteProfile, preset: ByeDpiPreset = ByeDpiPreset.COMBINED,
        reality: Boolean = false, wrongKey: Boolean = false, wrongUuid: Boolean = false,
        wrongHostname: Boolean = false, expectFailure: Boolean = false) = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("fixture_host") ?: "10.0.2.2"
        val publicKey = if (reality) File(requireNotNull(args.getString("fixture_reality_key_path"))).readText().trim() else ""
        val node = VlessConfig(name = "Local fixture", address = host, port = if (reality) 25443 else 24443,
            uuid = if (wrongUuid) "00000000-0000-4000-8000-000000000002" else "00000000-0000-4000-8000-000000000001",
            security = if (reality) "reality" else "tls", sni = "vpn.test.local", flow = if (reality) "xtls-rprx-vision" else "",
            publicKey = if (wrongKey) java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 10).toByte() }) else publicKey,
            shortId = if (reality) "aabb" else "")
        val proxy = LocalProbeProxy.allocate()
        val runner = ByeDpiRunner(context)
        var server: CommandServer? = null
        try {
            val dpiPort = if (profile == RouteProfile.BYEDPI) runner.start(preset) else null
            val json = JSONObject(SingBoxManager.generateConfig(null, node,
                AdaptiveRoutePolicy.safeSettings(AppSettings(), profile), probeProxy = proxy, antiDpiPort = dpiPort))
            // This fixture intentionally does not assert VpnService.Builder/TUN behavior.
            val all = json.getJSONArray("inbounds")
            json.put("inbounds", JSONArray().apply { for (i in 0 until all.length()) {
                val inbound = all.getJSONObject(i)
                if (inbound.getString("tag") == "probe-in") put(inbound)
            } })
            val outbounds = json.getJSONArray("outbounds")
            for (i in 0 until outbounds.length()) {
                val outbound = outbounds.getJSONObject(i)
                if (outbound.getString("tag") == "proxy" && !reality) outbound.getJSONObject("tls")
                    .put("certificate_path", certificate.absolutePath)
            }
            Libbox.checkConfig(json.toString())
            val platform = LibboxPlatformInterface(AttachedVpnService(context)) { error("Unexpected TUN in fixture") }
            val handler = object : CommandServerHandler {
                override fun getSystemProxyStatus(): SystemProxyStatus = NativeCallbackValues.disabledSystemProxy()
                override fun serviceReload() {}
                override fun serviceStop() {}
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun writeDebugMessage(message: String?) { android.util.Log.d("FixtureCore", message.orEmpty()) }
            }
            server = CommandServer(handler, platform)
            server.start()
            server.startOrReloadService(json.toString(), OverrideOptions())
            val ca = certificate.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", ca) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }.socketFactory
            val result = TunnelHealthChecker.probe(proxy,
                if (wrongHostname) "https://wrong.fixture.test:18443/generate_204" else "https://localhost:18443/generate_204", 204, 20000, tls)
            if (expectFailure) {
                assertNotEquals("Invalid route must not pass", 204, result.httpCode)
                assertNotEquals(DiagnosticFailure.NONE, result.failure)
                if (wrongHostname) assertEquals(DiagnosticFailure.TLS, result.failure)
                return@runBlocking
            }
            assertEquals("${profile.name}/${preset.name}: ${result.failure}", 204, result.httpCode)
            assertEquals(DiagnosticFailure.NONE, result.failure)
            val redirect = TunnelHealthChecker.probe(proxy, "https://localhost:18443/redirect", 204, 20000, tls)
            assertEquals(302, redirect.httpCode)
            assertEquals(DiagnosticFailure.HTTP, redirect.failure)
        } finally {
            runCatching { server?.closeService() }; server?.close(); runner.close()
        }
    }
    private class AttachedVpnService(context: Context) : VpnService() { init { attachBaseContext(context) } }
}
