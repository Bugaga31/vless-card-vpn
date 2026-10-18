package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkAdaptationTest {
    private fun wifi(handle: String, profile: String = handle) = NetworkHandoff.Snapshot(handle, profile)
    @Test fun wifiToWifiIsAChangeEvenWithTheSameRouterProfile() {
        val gate = NetworkHandoff(); gate.seed(wifi("101", "same"))
        val change = gate.update(wifi("102", "same"))!!
        assertTrue(change.online); assertTrue(gate.valid(change.revision))
        assertNull(gate.update(wifi("102", "same")))
    }
    @Test fun dhcpProfileChangeOnTheSameNetworkIsDetected() {
        val gate = NetworkHandoff(); gate.seed(wifi("101", "a"))
        assertNotNull(gate.update(wifi("101", "b")))
    }
    @Test fun lossReturnAndRapidSwitchInvalidateOldWork() {
        val gate = NetworkHandoff(); gate.seed(wifi("a"))
        val b = gate.update(wifi("b"))!!
        val lost = gate.update(null)!!; assertFalse(lost.online); assertFalse(gate.valid(b.revision))
        val back = gate.update(wifi("b"))!!; assertTrue(back.online)
        gate.update(wifi("c")); assertFalse(gate.valid(back.revision))
        gate.reset(); assertFalse(gate.valid(back.revision))
    }
    @Test fun hiddenSsidsDoNotMergeDifferentWifiSessions() {
        val a = Net.wifiKey("salt", "<unknown ssid>", "192.168.1.1", "1.1.1.1", "101")
        val b = Net.wifiKey("salt", "<unknown ssid>", "192.168.1.1", "1.1.1.1", "102")
        assertNotEquals(a, b); assertFalse(a.contains("192.168")); assertEquals("Wi-Fi", Net.family(a))
        assertEquals(Net.wifiKey("salt", "\"Home\"", "g", "d", "101"), Net.wifiKey("salt", "Home", "g", "d", "102"))
        assertNotEquals(Net.wifiKey("salt", "Home", "g", "d", "101"), Net.wifiKey("salt", "Work", "g", "d", "101"))
    }
    private fun fresh(block: () -> Unit) { val old = Brain.snapshot(); try { Brain.clear(); block() } finally { assertTrue(Brain.restore(old)) } }
    private val server = Server(name = "test", protocol = "vless", address = "example.org", port = 443, secret = "test")
    @Test fun learningOnOneWifiDoesNotTrainAnotherWifi() = fresh {
        val a = "Wi-Fi · WIFI_a"; val b = "Wi-Fi · WIFI_b"; val state = ServerState(tcpMs = 100)
        val initial = Brain.p(b, server, state)
        repeat(30) { Brain.learn(a, server, state, true) }
        assertEquals(initial, Brain.p(b, server, state), 1e-12)
        assertTrue(Brain.p(a, server, state) > initial)
        repeat(20) { Brain.learn(b, server, state, false) }
        assertTrue(Brain.p(a, server, state) > Brain.p(b, server, state))
        val saved = JSONObject(Brain.snapshot()); Brain.clear(); assertTrue(Brain.restore(saved.toString()))
        val restored = JSONObject(Brain.snapshot())
        assertTrue(saved.getJSONArray("contexts").similar(restored.getJSONArray("contexts")))
        assertEquals(saved.getInt("seen"), restored.getInt("seen"))
        assertEquals(saved.getJSONObject("trained").getDouble("w"), restored.getJSONObject("trained").getDouble("w"), 0.0)
    }
    @Test fun contextModelsAreBoundedAndMalformedImportIsAtomic() = fresh {
        repeat(80) { Brain.learn("Wi-Fi · WIFI_$it", server, ServerState(), true) }
        val saved = Brain.snapshot(); val json = JSONObject(saved)
        assertEquals(Brain.MAX_CONTEXTS, json.getJSONArray("contexts").length())
        json.getJSONArray("contexts").getJSONObject(0).put("key", "raw ssid")
        assertFalse(Brain.restore(json.toString())); assertEquals(saved, Brain.snapshot())
    }
    @Test fun automaticNewNetworkDoesNotInheritAnotherNetworksLearnedMask() {
        val a = "Wi-Fi · WIFI_a"; val b = "Wi-Fi · WIFI_b"
        val old = ServerState(maskId = "heavy", netMasks = mapOf(a to "heavy"))
        val next = old.forAutomaticNetwork(b)
        assertEquals("", next.maskFor(b)); assertEquals("heavy", next.maskFor(a))
        assertEquals("manual", ServerState(maskId = "manual").forAutomaticNetwork(b).maskFor(b))
        assertEquals("known", old.copy(netMasks = old.netMasks + (b to "known")).forAutomaticNetwork(b).maskFor(b))
    }
    @Test fun voiceIsOnlyForFirstLaunchAndDoesNotRepeatAfterUpdates() {
        val directory = java.nio.file.Files.createTempDirectory("welcome-policy").toFile()
        try {
            val marker = java.io.File(directory, "first-launch")
            assertTrue(WelcomePolicy.claimFirstLaunch(marker))
            assertFalse(WelcomePolicy.claimFirstLaunch(java.io.File(directory, "first-launch")))
            assertFalse(WelcomePolicy.claimFirstLaunch(marker))
            // Clearing app data / reinstalling creates a new installation; regular updates keep the file.
            assertTrue(marker.delete())
            assertTrue(WelcomePolicy.claimFirstLaunch(marker))
        } finally { directory.deleteRecursively() }
    }
    @Test fun welcomeIsQuietWhenDisabledAndDoesNotRepeatOnRotation() {
        val policy = WelcomePolicy()
        assertFalse(policy.claim(0, false, true)); assertFalse(policy.claim(0, true, false))
        assertTrue(policy.claim(0, true, true)); assertFalse(policy.claim(1000, true, true))
        assertTrue(policy.claim(60000, true, true)); assertFalse(policy.claim(5, true, true))
        assertTrue(Settings.fromJson(Settings(welcomeSound = false).toJson()).welcomeSound.not())
    }
}
