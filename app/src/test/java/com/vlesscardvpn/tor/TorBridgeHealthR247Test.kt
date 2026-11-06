package com.vlesscardvpn.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Base64

/** 1.2.135: bridges reconnect and rotate by themselves after the start, bounded and never off the Tor route. */
class TorBridgeHealthR247Test {
    private fun line(i: Int, transport: String = "obfs4") = if (transport == "obfs4")
        "Bridge obfs4 192.0.2.${i}:443 ${"%040X".format(i)} cert=" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { i.toByte() }) + " iat-mode=0"
    else "Bridge webtunnel 192.0.2.${i}:443 ${"%040X".format(i)} url=https://w$i.example.com/p ver=0.0.1"

    @Test fun shortLossesAreLeftToTorItself() {
        val h = TorBridgeHealth()
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, 0))
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, TorBridgeHealth.LOST_MS - 1))
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(true, true, TorBridgeHealth.LOST_MS + 1))
        // the loss clock starts again after a recovery
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, TorBridgeHealth.LOST_MS + 2))
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, 2 * TorBridgeHealth.LOST_MS))
    }

    @Test fun kickFirstThenOtherBridges() {
        val h = TorBridgeHealth()
        val lost = TorBridgeHealth.LOST_MS
        h.observe(false, true, 0)
        assertEquals(TorBridgeHealth.Action.KICK, h.observe(false, true, lost))
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, lost + TorBridgeHealth.KICK_WAIT_MS - 1))
        assertEquals(TorBridgeHealth.Action.ROTATE, h.observe(false, true, lost + TorBridgeHealth.KICK_WAIT_MS))
        // a proposal spends nothing; the next loss starts a fresh cycle
        assertTrue(h.canRestart(lost + TorBridgeHealth.KICK_WAIT_MS))
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, lost + TorBridgeHealth.KICK_WAIT_MS + 1))
    }

    @Test fun offlineOrSilentDaemonIsNeverABridgeFailure() {
        val h = TorBridgeHealth()
        repeat(20) { assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, false, it * 60_000L)) }
        repeat(20) { assertEquals(TorBridgeHealth.Action.NONE, h.observe(null, true, it * 60_000L)) }
        // clock going back never escalates
        h.observe(false, true, 1_000_000)
        assertEquals(TorBridgeHealth.Action.NONE, h.observe(false, true, 10))
    }

    @Test fun restartBudgetIsSharedAndRefills() {
        val h = TorBridgeHealth()
        assertTrue(h.allowRestart(0)); assertTrue(h.allowRestart(1)); assertTrue(h.allowRestart(2))
        assertFalse(h.canRestart(3)); assertFalse(h.allowRestart(3))
        assertTrue(h.allowRestart(TorBridgeHealth.WINDOW_MS))
        assertEquals(3, TorBridgeHealth.MAX_RESTARTS)
    }

    @Test fun torFlagsAreCombinedConservatively() {
        assertEquals(true, TorBridgeHealth.state(true, true))
        assertEquals(true, TorBridgeHealth.state(true, null))
        assertEquals(false, TorBridgeHealth.state(true, false))
        assertEquals(false, TorBridgeHealth.state(false, null))
        assertNull(TorBridgeHealth.state(null, null))
        assertNull(TorBridgeHealth.state(null, true))
    }

    @Test fun rotationWalksTheSavedCatalogAndNeverTakesTheFailingBridges() {
        val pool = (1..30).map { line(it) } + (1..3).map { line(it, "webtunnel") }
        val failing = (1..12).map { line(it) }
        val next = TorBridgeRefreshPolicy.rotation(pool, "obfs4", failing, failing.toSet())!!
        assertEquals((13..24).map { line(it) }, next)
        val after = TorBridgeRefreshPolicy.rotation(pool, "obfs4", next, (failing + next).toSet())!!
        // six untried first, then earlier tries that are not failing right now
        assertEquals((25..30).map { line(it) } + (1..6).map { line(it) }, after)
        assertNull(TorBridgeRefreshPolicy.rotation(pool, "obfs4", (1..30).map { line(it) }, emptySet()))
        assertNull(TorBridgeRefreshPolicy.rotation(pool, "none", failing, emptySet()))
        assertEquals((1..3).map { line(it, "webtunnel") }, TorBridgeRefreshPolicy.rotation(pool, "webtunnel", emptyList(), emptySet()))
        assertTrue(next.all { TorBridgeCatalog.normalize(it) == it })
    }

    private fun fake(handler: (String, java.io.OutputStream) -> Unit): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            runCatching {
                server.accept().use { s ->
                    val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    val w = s.getOutputStream()
                    while (true) {
                        val l = r.readLine() ?: break
                        handler(l, w); w.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun kickAlwaysEndsWithTheNetworkEnabled() {
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val state = java.util.concurrent.atomic.AtomicReference("0")
        val server = fake { l, w ->
            seen += l
            when {
                l.startsWith("AUTHENTICATE") -> w.write("250 OK\r\n".toByteArray())
                l == "SETCONF DisableNetwork=1" -> { state.set("1"); w.write("552 Transition not allowed\r\n".toByteArray()) }
                l == "SETCONF DisableNetwork=0" -> { state.set("0"); w.write("250 OK\r\n".toByteArray()) }
                l == "GETCONF DisableNetwork" -> w.write("250 DisableNetwork=${state.get()}\r\n".toByteArray())
                l == "GETINFO status/circuit-established" -> w.write("250-status/circuit-established=0\r\n250 OK\r\n".toByteArray())
                l == "GETINFO network-liveness" -> w.write("250-network-liveness=down\r\n250 OK\r\n".toByteArray())
                l == "SIGNAL ACTIVE" -> w.write("250 OK\r\n".toByteArray())
                else -> w.write("510 Unrecognized command\r\n".toByteArray())
            }
        }
        try {
            val c = TorControl("127.0.0.1", server.localPort, 2000)
            c.connect("AABB")
            // the failed first step still re-enables the network
            assertTrue(runCatching { c.kickNetwork() }.isFailure)
            assertEquals("SETCONF DisableNetwork=0", seen.last())
            assertFalse(c.ensureNetworkEnabled())
            state.set("1")
            assertTrue(c.ensureNetworkEnabled())
            assertEquals("0", state.get())
            assertEquals(false, c.circuitState())
            assertEquals(false, c.networkLive())
            c.signalActive()
            c.close()
            assertNull(c.circuitState()); assertNull(c.networkLive())
        } finally { server.close() }
    }

    @Test fun supervisionIsWiredAfterTheStart() {
        val path = "src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"
        val s = listOf(File("app/$path"), File(path)).first { it.exists() }.readText()
        val watch = s.substringAfter("private suspend fun watchTorBootstrap(").substringBefore("private var torJob")
        assertTrue(watch.contains("superviseTor(bridge, lines, ::reachable)"))
        assertTrue(watch.contains("TorBridgeUpdates.replacementFor(this@TunnelService, bridge, lines, control?.entryGuards())"))
        assertTrue(watch.contains("torHealth.allowRestart("))
        // a suspected loss is confirmed by a real request before anything is restarted
        val supervise = watch.substringAfter("private suspend fun superviseTor(")
        assertTrue(supervise.indexOf("TorProbes.reachable()") in 1 until supervise.indexOf("replacementFor("))
        assertTrue(supervise.contains("ensureNetworkEnabled()"))
        assertTrue(s.contains("kickFirst = upstreamChanged"))
    }
}
