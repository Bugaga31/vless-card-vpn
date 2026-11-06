package com.vlesscardvpn.core

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NetworkValidationWakeTest {
    @Test fun wakesOnlyOnSelectedNetworksValidationRecovery() {
        val gate = NetworkValidationWake()
        assertFalse(gate.observe("wifi", "wifi", true)) // baseline isn't a recovery
        assertFalse(gate.observe("mobile", "wifi", false)) // ignore a background network
        assertFalse(gate.observe("wifi", "wifi", true))
        assertFalse(gate.observe("wifi", "wifi", false))
        assertFalse(gate.observe("mobile", "wifi", true))
        assertTrue(gate.observe("wifi", "wifi", true))
        assertFalse(gate.observe("wifi", "wifi", true)) // no repeated wake on callback bursts
        assertFalse(gate.observe("mobile", "mobile", true)) // new handle: handoff owns it
        gate.reset()
        assertFalse(gate.observe("mobile", "mobile", true))
    }

    @Test fun networkHintInterruptsOfflineWaitWithoutWaitingForScreen() = runBlocking {
        val screen = Channel<Unit>(Channel.CONFLATED); val network = Channel<Unit>(Channel.CONFLATED)
        val waiting = async { WatchdogSignals.wait(screen, network, true, 2000) }
        delay(25); network.trySend(Unit)
        assertEquals(WatchdogSignals.Wake.NETWORK, waiting.await())
    }

    @Test fun healthyRouteDoesNotWakeForValidationChanges() = runBlocking {
        val screen = Channel<Unit>(Channel.CONFLATED); val network = Channel<Unit>(Channel.CONFLATED)
        network.trySend(Unit)
        assertEquals(WatchdogSignals.Wake.TIMER, WatchdogSignals.wait(screen, network, false, 50))
        assertEquals(WatchdogSignals.Wake.TIMER, WatchdogSignals.wait(screen, network, true, 50))
    }

    @Test fun screenWakeAndOrdinaryOfflinePollingStillWork() = runBlocking {
        val screen = Channel<Unit>(Channel.CONFLATED); val network = Channel<Unit>(Channel.CONFLATED)
        screen.trySend(Unit)
        assertEquals(WatchdogSignals.Wake.SCREEN, WatchdogSignals.wait(screen, network, false, 500))
        assertEquals(WatchdogSignals.Wake.TIMER, WatchdogSignals.wait(screen, network, true, 50))
    }

    @Test fun validationOnlyWakesTheExistingConfirmedProbe() {
        val f = listOf(File("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"), File("app/src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")).first { it.exists() }
        val text = f.readText()
        val callback = text.substringAfter("override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {").substringBefore("override fun onLinkPropertiesChanged")
        assertTrue(callback.contains("validationWake.observe"))
        assertTrue(callback.contains("networkWakeSignals.trySend(Unit)"))
        assertFalse(callback.contains("connect("))
        val watchdog = text.substringAfter("private fun watchdog()").substringBefore("private var standbyJob")
        assertTrue(watchdog.contains("WatchdogSignals.wait(screenSignals, networkWakeSignals, offline, period)"))
        assertTrue(watchdog.contains("Tester.probeSocks("))
        assertTrue(watchdog.contains("confirmLoss(port, settings.testUrl)"))
        assertTrue(watchdog.contains("pauseWatchdog(asleep(),autoWatch)"))
    }
}
