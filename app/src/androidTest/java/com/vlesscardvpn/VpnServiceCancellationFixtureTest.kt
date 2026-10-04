package com.vlesscardvpn

import android.app.ActivityManager
import android.content.Context
import android.net.VpnService
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.worker.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual FGS/service cancellation against a loopback TLS blackhole; never a successful-route claim. */
class VpnServiceCancellationFixtureTest {
    private class Blackhole : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val tlsReached = CountDownLatch(1)
        private val peers = CopyOnWriteArrayList<Socket>()
        private val accept = Thread {
            try {
                while (!server.isClosed) {
                    val peer = server.accept(); peers.add(peer)
                    Thread {
                        try {
                            peer.use {
                                it.soTimeout = 12000
                                val input = it.getInputStream()
                                // TCP preflight sends no bytes; a TLS handshake starts with record type 22.
                                if (input.read() == 22) tlsReached.countDown()
                                while (input.read() != -1) { }
                            }
                        } catch (_: Exception) { }
                    }.apply { isDaemon = true; start() }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); peers.forEach { runCatching { it.close() } }; accept.join(1000) }
    }
    @Test fun manualConnectionCancelsNativeHandshakeAndStopsService(): Unit = runBlocking { exercise(false) }
    @Test fun favoritesOnlyAutoCancelsWithoutImportingPublicNodes(): Unit = runBlocking { exercise(true) }

    private suspend fun exercise(auto: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue("Only explicitly enabled emulator fixtures", InstrumentationRegistry.getArguments().getString("fixture_full_tun") == "1")
        assertTrue("Do not manipulate VPN permission on a physical phone", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        assertNull(VpnService.prepare(context))
        val repo = AppRepository(context)
        val original = repo.settingsFlow.value
        val id = "service-cancel-fixture-" + UUID.randomUUID().toString()
        try {
            repo.updateSettings { it.copy(autoSelect = false, autopilotAllowedOnlyFavorites = true,
                autoReconnectOnNetworkChange = false, enableRuDirect = false, enableAdBlock = false,
                evasionStrategy = "stable_tls", enableFragmentation = false, enableSniRotation = false,
                // This fixture covers cancellation while the HTTPS gate is pending (not the ping-only mode).
                connectCheckMode = com.vlesscardvpn.domain.ConnectCheckMode.HTTPS, byeDpiCustomArgs = "") }
            assertTrue("Fixture must not select any other saved favorites", repo.getAllConfigs().none { it.isFavorite })
            Blackhole().use { endpoint ->
                val node = VlessConfig(id = id, name = "Controlled cancellation fixture", address = "127.0.0.1",
                    port = endpoint.port, uuid = "00000000-0000-4000-8000-000000000001", security = "tls",
                    sni = "fixture.invalid", flow = "", isFavorite = true)
                repo.addConfig(node)
                // Keep the app visibly foreground: do not bypass Android FGS restrictions.
                ActivityScenario.launch(MainActivity::class.java).use {
                    if (auto) VlessVpnService.startAuto(context) else VlessVpnService.startVpn(context, node)
                    assertTrue("Native outbound must actually enter TLS, not just publish CONNECTING",
                        withContext(Dispatchers.IO) { endpoint.tlsReached.await(8, TimeUnit.SECONDS) })
                    assertEquals(VpnStatus.CONNECTING, VlessVpnService.vpnStats.value.status)
                    withTimeout(2000) {
                        while (TunnelHealthChecker.activeProxy == null && VlessVpnService.vpnStats.value.status == VpnStatus.CONNECTING) delay(20)
                    }
                    assertNotNull("Core must own its authenticated probe while connecting", TunnelHealthChecker.activeProxy)
                    VlessVpnService.stopVpn(context)
                    withTimeout(5000) {
                        while (VlessVpnService.vpnStats.value.status != VpnStatus.DISCONNECTED) delay(25)
                    }
                    assertNull("Cancelled session must not retain a probe", TunnelHealthChecker.activeProxy)
                    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    withTimeout(5000) {
                        @Suppress("DEPRECATION")
                        while (manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == VlessVpnService::class.java.name }) delay(25)
                    }
                    delay(500) // Observe that a late result cannot revive CONNECTED/ERROR after cancellation.
                    assertEquals(VpnStatus.DISCONNECTED, VlessVpnService.vpnStats.value.status)
                }
            }
        } finally {
            // Do not recreate an already stopped service merely to send DISCONNECT.
            if (VlessVpnService.vpnStats.value.status != VpnStatus.DISCONNECTED) {
                runCatching { VlessVpnService.stopVpn(context) }
                runCatching { withTimeout(5000) { while (VlessVpnService.vpnStats.value.status != VpnStatus.DISCONNECTED) delay(25) } }
            }
            context.stopService(android.content.Intent(context, VlessVpnService::class.java))
            repo.deleteConfig(id)
            repo.updateSettings { original }
            repo.close()
        }
    }
}
