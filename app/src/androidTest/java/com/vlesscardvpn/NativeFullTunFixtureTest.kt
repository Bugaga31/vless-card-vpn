package com.vlesscardvpn

import android.content.Context
import android.content.ContextWrapper
import com.vlesscardvpn.worker.VlessVpnService
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
import java.util.UUID

/** Opt-in emulator-only real TUN + UDP DNS + verified HTTPS from another application UID. */
@RunWith(AndroidJUnit4::class)
class NativeFullTunFixtureTest {
    private lateinit var context: Context
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit emulator fixture required", args.getString("fixture_full_tun") == "1")
        assertTrue("Never auto-grant VPN on a physical phone", Build.HARDWARE in setOf("ranchu", "goldfish"))
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull("Emulator script must explicitly prepare VPN permission", VpnService.prepare(context))
        Seq.setContext(context)
        Libbox.setup(SetupOptions().apply {
            basePath = File(context.filesDir, "full-tun-fixture").apply { mkdirs() }.absolutePath
            workingPath = basePath; tempPath = context.cacheDir.absolutePath; fixAndroidStack = true
        })
    }
    @Test fun gvisorCompatibleTunDnsHttpsAndRestart() = exercise(RouteProfile.COMPATIBLE, stack = "gvisor")
    @Test fun compatibleTunDnsHttpsAndRestart() = exercise(RouteProfile.COMPATIBLE)
    @Test fun fragmentedTunDnsHttpsAndRestart() = exercise(RouteProfile.FRAGMENT)
    @Test fun tcpSplitTunDnsHttpsAndRestart() = exercise(RouteProfile.BYEDPI, ByeDpiPreset.TCP_ONLY)
    @Test fun recordSplitTunDnsHttpsAndRestart() = exercise(RouteProfile.BYEDPI, ByeDpiPreset.TLS_RECORD_ONLY)
    @Test fun combinedSplitTunDnsHttpsAndRestart() = exercise(RouteProfile.BYEDPI, ByeDpiPreset.COMBINED)

    private fun exercise(profile: RouteProfile, preset: ByeDpiPreset = ByeDpiPreset.COMBINED, stack: String = "mixed") = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("fixture_host") ?: "10.0.2.2"
        val node = VlessConfig(name = "Full TUN fixture", address = host, port = 24443,
            uuid = "00000000-0000-4000-8000-000000000001", security = "tls", sni = "vpn.test.local", flow = "")
        val proxy = LocalProbeProxy.allocate()
        val runner = ByeDpiRunner(context)
        try {
            val dpiPort = if (profile == RouteProfile.BYEDPI) runner.start(preset) else null
            val config = JSONObject(SingBoxManager.generateConfig(null, node,
                AdaptiveRoutePolicy.safeSettings(AppSettings(), profile), probeProxy = proxy, antiDpiPort = dpiPort))
            // Fixture-only CA and DNS resolver. Production trust/security settings are unchanged.
            val outs = config.getJSONArray("outbounds")
            for (i in 0 until outs.length()) if (outs.getJSONObject(i).getString("tag") == "proxy") {
                outs.getJSONObject(i).getJSONObject("tls").put("certificate_path", requireNotNull(args.getString("fixture_ca_path")))
            }
            config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
                .put("address", "udp://127.0.0.1:15353").put("detour", "proxy")
            val inbounds = config.getJSONArray("inbounds")
            for (i in 0 until inbounds.length()) if (inbounds.getJSONObject(i).getString("tag") == "tun-in") {
                inbounds.getJSONObject(i).put("stack", stack)
            }
            Libbox.checkConfig(config.toString())
            // Re-open the actual TUN after clean shutdown. Each cycle uses a fresh helper nonce.
            repeat(2) { cycle ->
                var server: CommandServer? = null
                var descriptor: ParcelFileDescriptor? = null
                try {
                    val adapter = LibboxPlatformInterface(registeredVpnService(context)) { descriptor = it }
                    val handler = object : CommandServerHandler {
                        override fun getSystemProxyStatus(): SystemProxyStatus = NativeCallbackValues.disabledSystemProxy()
                        override fun serviceReload() {}
                        override fun serviceStop() {}
                        override fun setSystemProxyEnabled(enabled: Boolean) {}
                        override fun writeDebugMessage(message: String?) { android.util.Log.d("FullTunFixture", message.orEmpty()) }
                    }
                    server = CommandServer(handler, adapter); server.start(); server.startOrReloadService(config.toString(), OverrideOptions())
                    assertNotNull("Native core must actually call VpnService.Builder.establish", descriptor)
                    val nonce = UUID.randomUUID().toString().replace("-", "")
                    shell("am force-stop com.vlesscardvpn.netprobe")
                    shell("am start -W -n com.vlesscardvpn.netprobe/.ProbeActivity --es nonce $nonce")
                    val deadline = System.currentTimeMillis() + 30000
                    var result: JSONObject? = null
                    while (System.currentTimeMillis() < deadline) {
                        val text = shell("run-as com.vlesscardvpn.netprobe cat files/result.json")
                        val parsed = runCatching { JSONObject(text) }.getOrNull()
                        if (parsed?.optString("nonce") == nonce) { result = parsed; break }
                        Thread.sleep(100)
                    }
                    assertNotNull("Separate-UID helper did not finish", result)
                    assertNotEquals("Helper must not share the excluded VPN UID", context.applicationInfo.uid, result!!.getInt("uid"))
                    assertTrue("Cycle $cycle: helper must use the Android VPN network", result!!.optBoolean("vpn"))
                    assertEquals("Cycle $cycle: DNS must go through the controlled tunnel", "198.18.0.1", result!!.optString("address"))
                    assertEquals("$profile/$preset/$stack cycle $cycle: ${result!!.optString("stage")}/${result!!.optString("failure")}", 204, result!!.optInt("code", -1))
                } finally {
                    runCatching { server?.closeService() }; server?.close()
                    runCatching { descriptor?.close() }
                    shell("am force-stop com.vlesscardvpn.netprobe")
                }
            }
        } finally { runner.close() }
    }
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }
    // Android Builder identifies the service by its actual class name. An anonymous
    // VpnService subclass cannot establish a TUN unless it is in the manifest.
    // Attach only the public SDK ContextWrapper base context; do not bypass VPN
    // permissions or invoke hidden Service.attach APIs. Service lifecycle/Auto are
    // intentionally outside this platform-adapter fixture.
    private fun registeredVpnService(context: Context): VpnService = VlessVpnService().also { service ->
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, context)
    }
}
