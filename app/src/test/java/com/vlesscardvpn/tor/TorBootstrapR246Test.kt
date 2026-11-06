package com.vlesscardvpn.tor

import com.vlesscardvpn.core.TorBootstrapStatus
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/** Tor start progress: control-protocol framing, whitelisted parsing, stall detection and the Home status texts. */
class TorBootstrapR246Test {
    private val notice = "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=45 TAG=requesting_descriptors SUMMARY=\"Asking for relay descriptors\"\n250 OK"
    private val warn = "250-status/bootstrap-phase=WARN BOOTSTRAP PROGRESS=10 TAG=conn_done SUMMARY=\"Connected to a relay\" " +
        "WARNING=\"Connection timed out \\\"really\\\"\" REASON=TIMEOUT COUNT=7 RECOMMENDATION=warn HOSTID=\"\$0123456789ABCDEF0123456789ABCDEF01234567\" HOSTADDR=\"203.0.113.9:443\"\n250 OK"

    @Test fun parsesNoticePhase() {
        val p = TorBootstrap.parse(notice)!!
        assertEquals(45, p.progress); assertEquals("requesting_descriptors", p.tag); assertFalse(p.warning); assertFalse(p.done)
        assertEquals("Загрузка описаний узлов", TorBootstrap.label(p.tag))
    }

    @Test fun parsesWarningWithoutExposingBridgeAddress() {
        val p = TorBootstrap.parse(warn)!!
        assertTrue(p.warning); assertEquals("TIMEOUT", p.reason); assertEquals(7, p.count); assertTrue(p.recommendWarn)
        assertFalse(p.toString().contains("203.0.113.9")); assertFalse(p.toString().contains("timed out"))
        assertEquals("мосты не отвечают", TorBootstrap.reason(p.reason))
    }

    @Test fun quotedValuesAndEscapesAreSplitCorrectly() {
        val k = TorBootstrap.keywords("BOOTSTRAP PROGRESS=5 SUMMARY=\"a b \\\"c\\\"\" TAG=conn")
        assertEquals("5", k["PROGRESS"]); assertEquals("a b \"c\"", k["SUMMARY"]); assertEquals("conn", k["TAG"])
    }

    @Test fun rejectsMissingOrImpossibleProgress() {
        assertNull(TorBootstrap.parse("250 OK"))
        assertNull(TorBootstrap.parse("250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=140 TAG=done\n250 OK"))
        assertNull(TorBootstrap.parse("250-status/bootstrap-phase=NOTICE BOOTSTRAP TAG=done\n250 OK"))
        assertEquals("", TorBootstrap.parse("250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=3 TAG=Weird<b>\n250 OK")!!.tag)
    }

    @Test fun unknownTagsAndReasonsUseFixedTexts() {
        assertEquals("Подключение", TorBootstrap.label("something_new"))
        assertEquals("мост недоступен", TorBootstrap.reason("NEWREASON"))
        assertEquals("", TorBootstrap.reason(""))
        assertEquals("Готово", TorBootstrap.label("done"))
    }

    private fun phase(p: Int) = TorBootstrap.Phase(p, "conn")

    @Test fun watchReportsProgressStallAndDone() {
        val w = TorBootstrap.Watch(stallMs = 60_000)
        assertEquals(TorBootstrap.Verdict.STARTING, w.observe(null, 0))
        assertEquals(TorBootstrap.Verdict.PROGRESS, w.observe(phase(10), 1_000))
        assertEquals(TorBootstrap.Verdict.PROGRESS, w.observe(phase(10), 60_999))
        assertEquals(TorBootstrap.Verdict.STALLED, w.observe(phase(10), 61_000))
        assertEquals(TorBootstrap.Verdict.PROGRESS, w.observe(phase(15), 62_000)) // moving again clears the stall
        assertEquals(TorBootstrap.Verdict.DONE, w.observe(phase(100), 63_000))
    }

    @Test fun silentDaemonStallsFromStartAndClockJumpNeverStalls() {
        val w = TorBootstrap.Watch(stallMs = 60_000)
        assertEquals(TorBootstrap.Verdict.STARTING, w.observe(null, 1_000))
        assertEquals(TorBootstrap.Verdict.STALLED, w.observe(null, 61_000))
        val back = TorBootstrap.Watch(stallMs = 60_000)
        back.observe(phase(20), 100_000)
        assertNotEquals(TorBootstrap.Verdict.STALLED, back.observe(phase(20), 5_000))
    }

    @Test fun lowerProgressDoesNotResetTheStallClock() {
        val w = TorBootstrap.Watch(stallMs = 60_000)
        w.observe(phase(50), 0); w.observe(phase(10), 30_000)
        assertEquals(TorBootstrap.Verdict.STALLED, w.observe(phase(50), 60_000))
        assertEquals(50, w.progress)
    }

    @Test fun reusedDaemonDoesNotFlashZeroPercent() {
        TorBootstrapStatus.clear()
        val t = TorBootstrapStatus.begin("obfs4")
        val before = TorBootstrapStatus.state.value
        assertTrue(before.active); assertFalse(before.starting)
        assertEquals("", TorBootstrapStatus.label(before)); assertEquals("", TorBootstrapStatus.suffix(before))
        TorBootstrapStatus.publish(t, phase(100), false)
        assertTrue(TorBootstrapStatus.state.value.done); assertFalse(TorBootstrapStatus.state.value.starting)
        TorBootstrapStatus.clear()
    }

    @Test fun statusRejectsLateSamplesAndClearsHintWhenDone() {
        TorBootstrapStatus.clear()
        val old = TorBootstrapStatus.begin("obfs4")
        val fresh = TorBootstrapStatus.begin("obfs4")
        assertFalse(TorBootstrapStatus.publish(old, phase(90), false))
        assertTrue(TorBootstrapStatus.publish(fresh, TorBootstrap.parse(warn), true, TorBootstrapStatus.Hint.TRY_OTHER))
        val stalled = TorBootstrapStatus.state.value
        assertEquals(10, stalled.progress); assertTrue(stalled.stalled)
        assertEquals("Tor подключается: 10% · Подключение к мосту", TorBootstrapStatus.label(stalled))
        assertTrue(TorBootstrapStatus.detail(stalled).startsWith("Мосты не отвечают."))
        assertTrue(TorBootstrapStatus.detail(stalled).contains("WebTunnel"))
        assertEquals(" (10% · Подключение к мосту)", TorBootstrapStatus.suffix(stalled))
        // A missing sample keeps the last progress on screen.
        assertTrue(TorBootstrapStatus.publish(fresh, null, false))
        assertEquals(10, TorBootstrapStatus.state.value.progress)
        assertTrue(TorBootstrapStatus.publish(fresh, phase(100), true, TorBootstrapStatus.Hint.TRY_OTHER))
        val done = TorBootstrapStatus.state.value
        assertTrue(done.done); assertFalse(done.stalled); assertEquals(TorBootstrapStatus.Hint.NONE, done.hint)
        assertEquals("", TorBootstrapStatus.suffix(done)); assertEquals("", TorBootstrapStatus.detail(done))
        TorBootstrapStatus.clear()
        assertFalse(TorBootstrapStatus.publish(fresh, phase(5), false))
        assertEquals("", TorBootstrapStatus.label(TorBootstrapStatus.state.value))
    }

    @Test fun webtunnelSuggestsObfs4AndRefreshHintIsShown() {
        TorBootstrapStatus.clear()
        val t = TorBootstrapStatus.begin("webtunnel")
        TorBootstrapStatus.publish(t, phase(5), true, TorBootstrapStatus.Hint.TRY_OTHER)
        assertTrue(TorBootstrapStatus.detail(TorBootstrapStatus.state.value).contains("obfs4"))
        TorBootstrapStatus.publish(t, phase(5), true, TorBootstrapStatus.Hint.REFRESHING)
        assertTrue(TorBootstrapStatus.detail(TorBootstrapStatus.state.value).contains("свежий список"))
        TorBootstrapStatus.clear()
    }

    @Test fun progressingStartStaysPendingOnlyWhileMovingAndBounded() {
        val moving = TorBootstrapStatus.Info(active = true, progress = 60, tag = "loading_descriptors", sampled = true)
        assertFalse(TorBootstrapStatus.progressing(moving.copy(sampled = false), 90_000))
        assertTrue(TorBootstrapStatus.progressing(moving, 90_000))
        assertFalse(TorBootstrapStatus.progressing(moving.copy(stalled = true), 90_000))
        assertFalse(TorBootstrapStatus.progressing(moving, TorBootstrapStatus.PENDING_MAX_MS + 1))
        assertFalse(TorBootstrapStatus.progressing(moving.copy(progress = 0), 10_000))
        assertFalse(TorBootstrapStatus.progressing(moving.copy(progress = 100), 10_000))
        assertFalse(TorBootstrapStatus.progressing(TorBootstrapStatus.Info(), 10_000))
    }

    @Test fun stalledRefreshIsEarlierButStillBoundedAndConsented() {
        val now = 10 * 60 * 60_000L
        val hourAgo = now - 60 * 60_000L
        assertFalse(TorBridgeRefreshPolicy.due(true, "obfs4", now, hourAgo, hourAgo))
        assertTrue(TorBridgeRefreshPolicy.dueWhenStalled(true, "obfs4", now, hourAgo, hourAgo))
        assertFalse(TorBridgeRefreshPolicy.dueWhenStalled(false, "obfs4", now, hourAgo, hourAgo))
        assertFalse(TorBridgeRefreshPolicy.dueWhenStalled(true, "none", now, hourAgo, hourAgo))
        assertFalse(TorBridgeRefreshPolicy.dueWhenStalled(true, "obfs4", now, now - 10 * 60_000L, hourAgo))
        assertFalse(TorBridgeRefreshPolicy.dueWhenStalled(true, "obfs4", now, hourAgo, now - 60_000L))
        assertTrue(TorBridgeRefreshPolicy.dueWhenStalled(true, "webtunnel", now, 0, 0))
    }

    @Test fun stalledRefreshPrefersBridgesThatWereNotJustTried() {
        val cert = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52))
        val parsed = TorBridgeCatalog.parse((1..20).joinToString("\n") { "obfs4 192.0.2.$it:443 ${"%040X".format(it)} cert=$cert iat-mode=0" })
        val first = parsed.forTransport("obfs4")
        assertEquals(12, first.size)
        val next = parsed.forTransport("obfs4", first.toSet())
        assertEquals(12, next.size)
        assertEquals(8, next.count { it !in first })
        assertTrue(next.take(8).none { it in first }) // untried bridges come first, the old ones only fill up
        assertEquals(next.joinToString("\n"),
            TorBridgeRefreshPolicy.replacement(parsed, "obfs4", "old", "obfs4", "old", true, 1, 1, first.toSet()))
        assertEquals(first, parsed.forTransport("obfs4", emptySet()))
    }

    /** Fake ControlPort that answers GETINFO with a data block and interleaves an asynchronous event. */
    private fun fake(handler: (String, java.io.OutputStream) -> Unit): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            runCatching {
                server.accept().use { s ->
                    val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                    val w = s.getOutputStream()
                    while (true) {
                        val line = r.readLine() ?: break
                        handler(line, w); w.flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun dataBlocksAndAsyncEventsDoNotDesynchroniseReplies() {
        val server = fake { line, w ->
            when {
                line.startsWith("AUTHENTICATE") -> w.write("250 OK\r\n".toByteArray())
                line == "GETINFO circuit-status" -> w.write(("650 CIRC 1 BUILT\r\n250+circuit-status=\r\n1 BUILT \$AAAA\r\n..dot line\r\n.\r\n250 OK\r\n").toByteArray())
                line == "GETINFO status/bootstrap-phase" -> w.write(("650+NS\r\nr relay\r\n.\r\n650 OK\r\n" + notice.replace("\n", "\r\n") + "\r\n").toByteArray())
                line == "GETINFO status/circuit-established" -> w.write("250-status/circuit-established=1\r\n250 OK\r\n".toByteArray())
                else -> w.write("510 Unrecognized command\r\n".toByteArray())
            }
        }
        try {
            val c = TorControl("127.0.0.1", server.localPort, 2000)
            c.connect("AABB")
            val circuits = c.command("GETINFO", "circuit-status")
            assertTrue(circuits.startsWith("250+circuit-status="))
            assertTrue(circuits.contains("1 BUILT"))
            assertTrue(circuits.contains("\n.dot line")) // dot-stuffing removed
            assertTrue(circuits.endsWith("250 OK"))
            assertFalse(circuits.contains("650"))
            val boot = c.bootstrap()!!
            assertEquals(45, boot.progress)
            assertTrue(c.circuitEstablished())
            c.close()
        } finally { server.close() }
    }

    @Test fun errorReplyToBootstrapIsAnIoFailure() {
        val server = fake { line, w ->
            if (line.startsWith("AUTHENTICATE")) w.write("250 OK\r\n".toByteArray()) else w.write("552 Unrecognized key\r\n".toByteArray())
        }
        try {
            val c = TorControl("127.0.0.1", server.localPort, 2000)
            c.connect("AABB")
            assertThrows(java.io.IOException::class.java) { c.bootstrap() }
            c.close()
        } finally { server.close() }
    }

    @Test fun rotatorAsksForReconnectAfterRepeatedControlFailures() {
        var fail = false
        val control = object : TorControl("127.0.0.1", 1, 100) {
            override fun connect(cookieHex: String) {}
            override fun signalNewNym() { if (fail) throw java.io.IOException("daemon restarted") }
            override fun setExitCountries(countries: List<String>) {}
            override fun clearExitCountries() {}
            override fun close() {}
        }
        var t = 0L
        val rotator = TorRotator(control, TorConnectionPolicy.rotation(com.vlesscardvpn.model.Settings(torRotationSeconds = 30)) { t }, cookieHex = "01")
        assertTrue(rotator.needsReconnect)
        assertTrue(rotator.connect()); assertFalse(rotator.needsReconnect)
        fail = true
        t = 30_000; assertFalse(rotator.tick(t)); assertFalse(rotator.needsReconnect) // one failure may be transient
        t = 36_000; assertFalse(rotator.tick(t)); assertTrue(rotator.needsReconnect)
        fail = false
        assertTrue(rotator.connect()); assertFalse(rotator.needsReconnect)
        rotator.close(); assertTrue(rotator.needsReconnect)
    }
}
