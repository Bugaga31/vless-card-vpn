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

/** 1.2.135: Tor's own verdict per bridge drives the rotation; built-in bridges; slow downloads are not stalls. */
class TorBridgeMemoryR248Test {
    private fun fp(i: Int) = "%040X".format(i)
    private fun line(i: Int, transport: String = "obfs4") = if (transport == "obfs4")
        "Bridge obfs4 192.0.2.${i}:443 ${fp(i)} cert=" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { i.toByte() }) + " iat-mode=0"
    else "Bridge webtunnel 192.0.2.${i}:443 ${fp(i)} url=https://w$i.example.com/p ver=0.0.1"

    @Test fun entryGuardsReplyIsParsedStrictly() {
        val reply = "250+entry-guards=\n\$${fp(1)}~relay1 up\n\$${fp(2).lowercase()} down 2026-01-01 10:00:00\n" +
            "\$${fp(3)}=named never-connected\n\$${fp(4)} unusable 2026-01-01 10:00:00\nnot a guard up\n\$${fp(5)}~x sideways\n250 OK"
        val g = TorBridgeMemory.parseGuards(reply)
        assertEquals(mapOf(fp(1) to "up", fp(2) to "down", fp(3) to "never-connected", fp(4) to "unusable"), g)
        assertTrue(TorBridgeMemory.parseGuards("250-entry-guards=\n250 OK").isEmpty())
        assertEquals(fp(7), TorBridgeMemory.fingerprint(line(7)))
        assertNull(TorBridgeMemory.fingerprint("Bridge obfs4 192.0.2.1:443 nothex cert=x"))
        val lines = (1..5).map { line(it) }
        assertEquals(listOf(line(1)), TorBridgeMemory.working(lines, g))
        assertEquals(listOf(line(2)), TorBridgeMemory.failing(lines, g))
    }

    @Test fun memoryKeepsWorkingBridgesAndForgetsFailuresAfterTtl() {
        val lines = (1..4).map { line(it) }
        var m = TorBridgeMemory.note(TorBridgeMemory.State(), lines, mapOf(fp(1) to "up", fp(2) to "down", fp(3) to "never-connected"), 1_000)
        assertEquals(listOf(line(1)), m.good)
        assertEquals(setOf(fp(2)), TorBridgeMemory.bad(m, 1_000))
        // a bridge that comes back is no longer skipped; one that fails stops being «good»
        m = TorBridgeMemory.note(m, lines, mapOf(fp(1) to "down", fp(2) to "up"), 2_000)
        assertEquals(listOf(line(2)), m.good)
        assertEquals(setOf(fp(1)), TorBridgeMemory.bad(m, 2_000))
        assertTrue(TorBridgeMemory.bad(m, 2_000 + TorBridgeMemory.BAD_TTL_MS).isEmpty())
        assertTrue(TorBridgeMemory.bad(m, 1_500).isEmpty()) // clock went back: never skip on it
        val round = TorBridgeMemory.decode(TorBridgeMemory.encodeGood(m), TorBridgeMemory.encodeBad(m), "obfs4")
        assertEquals(m, round)
        // other transports, broken rows and unvalidated lines never come back from prefs
        val dirty = TorBridgeMemory.decode(line(1, "webtunnel") + "\ngarbage\n" + line(3), "zz 5\n${fp(9)} x\n${fp(8)} 77", "obfs4")
        assertEquals(listOf(line(3)), dirty.good); assertEquals(mapOf(fp(8) to 77L), dirty.bad)
        val many = TorBridgeMemory.note(TorBridgeMemory.State(), (1..20).map { line(it) }, (1..20).associate { fp(it) to "up" }, 5)
        assertEquals(TorBridgeMemory.GOOD_MAX, many.good.size)
    }

    @Test fun rotationKeepsWorkingBridgesAndSkipsRecentlyFailedOnes() {
        val pool = (1..30).map { line(it) }
        val failing = (1..12).map { line(it) }
        val keep = listOf(line(3), line(5))
        val bad = setOf(fp(13), fp(14))
        val good = listOf(line(20))
        val next = TorBridgeRefreshPolicy.rotation(pool, "obfs4", failing, failing.toSet(), keep = keep, good = good, bad = bad)!!
        assertEquals(listOf(line(3), line(5), line(20)) + (15..19).map { line(it) } + (21..24).map { line(it) }, next)
        assertTrue(next.none { it in failing && it !in keep })
        // every bridge «up» yet no route: at least one slot still changes
        val all = TorBridgeRefreshPolicy.rotation(pool, "obfs4", failing, failing.toSet(), keep = failing)!!
        assertEquals(failing.take(11) + line(13), all)
        // nothing fresh left (only recently failed ones): null, the caller decides to take the failed ones back
        assertNull(TorBridgeRefreshPolicy.rotation(pool.take(14), "obfs4", failing, failing.toSet(), bad = bad))
        assertEquals(listOf(line(13), line(14)), TorBridgeRefreshPolicy.rotation(pool.take(14), "obfs4", failing, failing.toSet()))
        // a kept line must belong to the current list and transport
        assertEquals(line(13), TorBridgeRefreshPolicy.rotation(pool, "obfs4", failing, failing.toSet(), keep = listOf(line(29), line(1, "webtunnel")))!!.first())
    }

    @Test fun freshSourceListAlsoKeepsWorkingBridgesAndSkipsFailedOnes() {
        val catalog = TorBridgeCatalog.Parsed((10..30).map { line(it) }, 0)
        val r = TorBridgeRefreshPolicy.replacement(catalog, "obfs4", "old", "obfs4", "old", true, 1, 1,
            avoid = setOf(line(10)), keep = listOf(line(2)), bad = setOf(fp(11)))!!.lines()
        assertEquals(line(2), r[0])
        assertEquals(12, r.size)
        assertFalse(line(10) in r); assertFalse(line(11) in r)
        assertNull(TorBridgeRefreshPolicy.replacement(TorBridgeCatalog.Parsed(emptyList(), 0), "obfs4", "old", "obfs4", "old", true, 1, 1, keep = listOf(line(2))))
    }

    @Test fun builtInBridgesAreValidAndStartTor() {
        val b = TorBuiltinBridges.lines("obfs4")
        assertEquals(7, b.size)
        assertEquals(7, b.mapNotNull { TorBridgeMemory.fingerprint(it) }.distinct().size)
        assertTrue(b.all { it.startsWith("Bridge obfs4 ") && TorBridgeCatalog.normalize(it) == it })
        assertTrue(TorBuiltinBridges.lines("webtunnel").isEmpty()); assertTrue(TorBuiltinBridges.lines("none").isEmpty())
        val torrc = TorCard.torrcEmbedded("/d", "/c", "/b", bridge = "obfs4", bridgeLines = b)
        assertTrue(b.all { torrc.contains(it) }); assertTrue(torrc.contains("UseBridges 1"))
        assertEquals(b, TorBridgeCatalog.selected((b + "Bridge webtunnel x").joinToString("\n"), "obfs4"))
        // the rotation reaches them last: after the saved catalog, never before it
        val pool = ((1..3).map { line(it) } + b).distinct()
        assertEquals((2..3).map { line(it) } + b, TorBridgeRefreshPolicy.rotation(pool, "obfs4", listOf(line(1)), setOf(line(1))))
    }

    @Test fun trafficWithoutProgressIsNotAStallUntilTheHardLimit() {
        val phase = TorBootstrap.Phase(20, "loading_status")
        val w = TorBootstrap.Watch(stallMs = 60_000)
        assertEquals(TorBootstrap.Verdict.PROGRESS, w.observe(phase, 0, 1_000))
        var t = 0L; var bytes = 1_000L
        while (t < TorBootstrap.HARD_STALL_MS - 30_000) {
            t += 30_000; bytes += TorBootstrap.ACTIVE_BYTES
            assertEquals("t=$t", TorBootstrap.Verdict.PROGRESS, w.observe(phase, t, bytes))
        }
        assertEquals(TorBootstrap.Verdict.STALLED, w.observe(phase, TorBootstrap.HARD_STALL_MS, bytes + TorBootstrap.ACTIVE_BYTES))
        // silence stalls after the usual minute; a trickle below the threshold is silence
        val quiet = TorBootstrap.Watch(stallMs = 60_000)
        quiet.observe(phase, 0, 1_000); quiet.observe(phase, 30_000, 2_000)
        assertEquals(TorBootstrap.Verdict.STALLED, quiet.observe(phase, 60_000, 3_000))
        // a restarted counter is not activity
        val reset = TorBootstrap.Watch(stallMs = 60_000)
        reset.observe(phase, 0, 900_000); reset.observe(phase, 10_000, 5)
        assertEquals(TorBootstrap.Verdict.STALLED, reset.observe(phase, 60_000, 5 + TorBootstrap.ACTIVE_BYTES - 1))
        // unknown traffic: progress alone decides, as before
        val plain = TorBootstrap.Watch(stallMs = 60_000)
        plain.observe(phase, 0)
        assertEquals(TorBootstrap.Verdict.STALLED, plain.observe(phase, 60_000, null))
        assertEquals(TorBootstrap.Verdict.DONE, plain.observe(TorBootstrap.Phase(100, "done"), 61_000, 1))
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

    @Test fun controlPortReportsTrafficAndBridgeVerdicts() {
        val server = fake { l, w ->
            when (l) {
                "GETINFO traffic/read" -> w.write("650 BW 1 2\r\n250-traffic/read=123456\r\n250 OK\r\n".toByteArray())
                "GETINFO entry-guards" -> w.write("250+entry-guards=\r\n\$${fp(1)}~a up\r\n\$${fp(2)} down 2026-01-01 00:00:00\r\n.\r\n250 OK\r\n".toByteArray())
                else -> w.write("250 OK\r\n".toByteArray())
            }
        }
        try {
            val c = TorControl("127.0.0.1", server.localPort, 2000)
            c.connect("AABB")
            assertEquals(123456L, c.trafficRead())
            assertEquals(mapOf(fp(1) to "up", fp(2) to "down"), c.entryGuards())
            c.close()
            assertNull(c.trafficRead()); assertNull(c.entryGuards())
        } finally { server.close() }
    }

    @Test fun verdictsAreWiredIntoStartStallAndSupervision() {
        val path = "src/main/java/com/vlesscardvpn/vpn/TunnelService.kt"
        val s = listOf(File("app/$path"), File(path)).first { it.exists() }.readText()
        val watch = s.substringAfter("private suspend fun watchTorBootstrap(").substringBefore("private suspend fun superviseTor(")
        assertTrue(watch.contains("watch.observe(phase, android.os.SystemClock.elapsedRealtime(), control?.trafficRead())"))
        assertTrue(watch.contains("noteHealth(this@TunnelService, bridge, lines, control?.entryGuards())"))
        val supervise = s.substringAfter("private suspend fun superviseTor(").substringBefore("private var torJob")
        assertTrue(supervise.contains("replacementFor(this@TunnelService, bridge, lines, guards)"))
        assertTrue(supervise.contains("if (healthy == true) c?.entryGuards()"))
        // the verdicts for a swap are read only after the real request through Tor confirmed the loss
        assertTrue(supervise.indexOf("TorProbes.reachable()") in 1 until supervise.indexOf("val guards = (control ?: reachable().also { control = it })?.entryGuards()"))
        assertTrue(s.contains("TorBridgeUpdates.adoptBuiltins(this@TunnelService, active.torBridge)"))
        val updates = listOf(File("app/src/main/java/com/vlesscardvpn/tor/TorBridgeUpdates.kt"), File("src/main/java/com/vlesscardvpn/tor/TorBridgeUpdates.kt")).first { it.exists() }.readText()
        assertTrue(updates.contains("TorBuiltinBridges.lines(transport)).distinct()"))
        assertTrue(updates.contains("attempt(bad) ?: run { seen.clear(); seen += failing; attempt(bad) } ?: attempt(emptySet())"))
    }
}
