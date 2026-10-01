package com.vlesscardvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import go.Seq
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/** Check the actual bundled core, not just a JVM JSON approximation. No live server is contacted. */
@RunWith(AndroidJUnit4::class)
class NativeConfigValidationTest {
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Seq.setContext(context)
        Libbox.setup(SetupOptions().apply {
            basePath = File(context.filesDir, "native-test").apply { mkdirs() }.absolutePath
            workingPath = basePath
            tempPath = context.cacheDir.absolutePath
            fixAndroidStack = true
        })
    }
    @Test fun authenticatedProbeAndDomainBootstrapPassNativeSchema() {
        val node = VlessConfig(name = "Test", address = "vpn.example.org", port = 443,
            uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "vpn.example.org")
        Libbox.checkConfig(SingBoxManager.generateConfig(null, node,
            com.vlesscardvpn.domain.AutoConnectPolicy.settings(AppSettings(), false),
            probeProxy = com.vlesscardvpn.domain.LocalProbeProxy(10810, "probe", "test-password")))
    }
    @Test fun antiDpiDetourPassesNativeSchema() {
        val node = VlessConfig(name = "Test", address = "vpn.example.org", port = 443,
            uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "vpn.example.org")
        Libbox.checkConfig(SingBoxManager.generateConfig(null, node,
            com.vlesscardvpn.domain.AdaptiveRoutePolicy.safeSettings(AppSettings(), com.vlesscardvpn.domain.RouteProfile.BYEDPI),
            probeProxy = com.vlesscardvpn.domain.LocalProbeProxy(10810, "probe", "password"), antiDpiPort = 10820))
    }
    @Test fun stableTlsPassesNativeSchema() {
        val node = VlessConfig(name = "Test", address = "vpn.example.org", port = 443,
            uuid = "00000000-0000-4000-8000-000000000001", security = "tls", flow = "", sni = "vpn.example.org")
        Libbox.checkConfig(SingBoxManager.generateConfig(null, node, AppSettings(evasionStrategy = "stable_tls")))
    }
    @Test fun stableRealityPassesNativeSchema() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val node = VlessConfig(name = "Test", address = "vpn.example.org", port = 443,
            uuid = "00000000-0000-4000-8000-000000000001", sni = "server.example.org", publicKey = key, shortId = "ab")
        Libbox.checkConfig(SingBoxManager.generateConfig(null, node, AppSettings(evasionStrategy = "stable_tls")))
    }
}
