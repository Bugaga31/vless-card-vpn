package com.vlesscardvpn.tor

import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.tor.TorBootstrap.Phase
import com.vlesscardvpn.tor.TorBootstrap.Verdict
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** 1.2.136: Tor Project's own bridges through VLESS, a quick swap when Tor reports every bridge dead, live bridge state. */
class TorMoatR249Test {
    private fun fp(i: Int) = "%040X".format(i)
    private fun cert(i: Int) = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52) { (i * 7).toByte() })
    private fun obfs(i: Int, host: String = "198.51.100.$i", mode: Int = 0) = "obfs4 $host:9443 ${fp(i)} cert=${cert(i)} iat-mode=$mode"
    private fun web(i: Int) = "webtunnel [2001:db8::$i]:443 ${fp(100 + i)} url=https://cdn$i.example.org/p$i ver=0.0.1"
    private fun source(path: String) = listOf(File("app/$path"), File(path)).first { it.exists() }.readText()

    @Test fun settingsReplyGivesOnlyValidObfs4AndWebTunnelLines() {
        val reply = JSONObject().put("country", "ru").put("settings", org.json.JSONArray()
            .put(JSONObject().put("bridges", JSONObject().put("type", "webtunnel").put("source", "bridgedb")
                .put("bridge_strings", org.json.JSONArray().put(web(1)).put("webtunnel broken"))))
            .put(JSONObject().put("bridges", JSONObject().put("type", "obfs4").put("source", "bridgedb")
                .put("bridge_strings", org.json.JSONArray().put(obfs(2)).put(obfs(3, host = "10.0.0.1")).put(web(4)).put(17))))
            .put(JSONObject().put("bridges", JSONObject().put("type", "snowflake").put("source", "builtin")))
            .put(JSONObject().put("bridges", JSONObject().put("type", "meek").put("bridge_strings", org.json.JSONArray().put("meek 0.0.2.0:3 ${fp(9)} url=https://x")))))
            .toString()
        val parsed = TorMoat.parse(reply)
        assertEquals(listOf("Bridge " + web(1), "Bridge " + obfs(2)), parsed.lines)
        // the broken line, a private obfs4 address, a WebTunnel line filed under obfs4 and a number; other transports are not counted
        assertEquals(4, parsed.rejected)
        assertTrue(TorMoat.parse("""{"errors":[{"code":404,"detail":"No provided transport is available for this country"}]}""").lines.isEmpty())
        assertThrows(org.json.JSONException::class.java) { TorMoat.parse("<html>blocked</html>") }
        assertThrows(IllegalArgumentException::class.java) { TorMoat.parse("{\"settings\":[]," + " ".repeat(TorMoat.MAX_BYTES) + "}") }
    }

    @Test fun requestAsksForRussiaAndTheAppsTransportsOnly() {
        val body = JSONObject(TorMoat.request())
        assertEquals("ru", body.getString("country"))
        assertEquals(listOf("obfs4", "webtunnel"), (0 until body.getJSONArray("transports").length()).map { body.getJSONArray("transports").getString(it) })
        assertEquals("https://bridges.torproject.org/moat/circumvention/settings", TorMoat.SETTINGS)
    }

    @Test fun newestTorProjectBridgesFirstThenKeptOnesBoundedPerTransport() {
        val fresh = listOf("Bridge " + obfs(1), "Bridge " + web(1))
        val kept = listOf("Bridge " + obfs(1, mode = 2)) + (2..12).map { "Bridge " + obfs(it) } + listOf("Bridge " + web(2), "Bridge " + web(1))
        val merged = TorMoat.merge(fresh, kept)
        assertEquals(listOf("Bridge " + obfs(1)) + (2..8).map { "Bridge " + obfs(it) } + listOf("Bridge " + web(1), "Bridge " + web(2)), merged)
        assertEquals(kept.filter { it.startsWith("Bridge obfs4 ") }.distinctBy { TorBridgeMemory.fingerprint(it) }.take(TorMoat.KEEP_MAX),
            TorMoat.merge(emptyList(), kept).filter { it.startsWith("Bridge obfs4 ") })
    }

    @Test fun torProjectIsAskedOnlyThroughTheVpnAndBridgeListsNeverGoIntoTor() {
        val updates = source("src/main/java/com/vlesscardvpn/tor/TorBridgeUpdates.kt")
        assertTrue(updates.contains("val official = via?.let { port -> async { try { TorMoat.fetch(port) }"))
        assertTrue(updates.contains("val via = com.vlesscardvpn.core.Tunnel.torUplink ?: com.vlesscardvpn.core.Tunnel.socks?.port"))
        val main = File(listOf("app/src/main/java", "src/main/java").first { File(it).isDirectory })
        val calls = main.walk().filter { it.isFile && it.extension == "kt" }.sumOf { Regex("TorMoat\\.fetch\\(").findAll(it.readText()).count() }
        assertEquals(1, calls)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { TorMoat.fetch(0) } }
        val service = source("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")
        val set = "Tunnel.torUplink = if (XrayConfigBuilder.torUplinks(settings)) com.vlesscardvpn.tor.TorCard.DEFAULT_PT_UPSTREAM_PORT else null"
        assertTrue(service.indexOf("Tunnel.socks = socks") in 1 until service.indexOf(set))
        assertTrue(service.contains("core?.stop(); core = null; Tunnel.wireguardKeys = emptySet(); Tunnel.torUplink = null"))
    }

    @Test fun torUplinkFlagMatchesTheBuiltConfig() {
        val reality = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp#Reality%20NL"
        val server = LinkParser.parse(reality)!!
        for (s in listOf(Settings(), Settings(torAll = true), Settings(torAll = true, torRotation = true), Settings(torRotation = true))) {
            val inbounds = JSONObject(XrayConfigBuilder.vpnConfig(listOf(server to null), s, null)).getJSONArray("inbounds")
            val uplink = (0 until inbounds.length()).map { inbounds.getJSONObject(it) }.firstOrNull { it.optString("tag") == XrayConfigBuilder.TOR_PT_UPLINK_INBOUND }
            assertEquals(s.toString(), uplink != null, XrayConfigBuilder.torUplinks(s))
            if (uplink != null) {
                assertEquals(TorCard.DEFAULT_PT_UPSTREAM_PORT, uplink.getInt("port"))
                assertEquals("127.0.0.1", uplink.getString("listen"))
            }
        }
    }

    private fun dead(count: Int, tag: String = "conn_done_pt", progress: Int = 2, warn: Boolean = true) =
        Phase(progress, tag, warning = true, reason = "CONNECTREFUSED", count = count, recommendWarn = warn)

    @Test fun torsOwnVerdictThatNoBridgeAnsweredStallsAfterTwentySeconds() {
        val w = TorBootstrap.Watch(bridges = 12)
        assertEquals(Verdict.PROGRESS, w.observe(Phase(2, "conn_done_pt"), 0, 0))
        assertEquals(Verdict.PROGRESS, w.observe(dead(10), 19_999, 900))
        assertEquals(Verdict.STALLED, w.observe(dead(10), 20_000, 1_000))
        // cached directory: stuck connecting for the first circuit at 80 %
        val cached = TorBootstrap.Watch(bridges = 3)
        assertEquals(Verdict.PROGRESS, cached.observe(Phase(80, "ap_conn"), 0, 0))
        assertEquals(Verdict.STALLED, cached.observe(dead(3, tag = "ap_conn", progress = 80), 20_000, 0))
    }

    @Test fun earlyStallNeedsEveryBridgeFailedNoTrafficAndNoAnsweredBridge() {
        fun at20(bridges: Int, phase: Phase, bytes: Long = 0): Verdict {
            val w = TorBootstrap.Watch(bridges = bridges)
            w.observe(Phase(phase.progress, phase.tag), 0, 0)
            var v = Verdict.STARTING
            for (t in 5_000L..20_000L step 5_000L) v = w.observe(phase, t, bytes * (t / 5_000L))
            return v
        }
        assertEquals(Verdict.PROGRESS, at20(12, dead(3)))                    // all closed for now, but 9 bridges not tried yet
        assertEquals(Verdict.PROGRESS, at20(12, dead(12, warn = false)))     // Tor itself does not recommend a warning
        assertEquals(Verdict.PROGRESS, at20(12, dead(12, tag = "handshake_done", progress = 15))) // a bridge answered
        assertEquals(Verdict.PROGRESS, at20(12, dead(12), bytes = 20_000))   // data still arrives from somewhere
        assertEquals(Verdict.PROGRESS, at20(0, dead(12)))                    // unknown bridge count: the minute rule only
        assertEquals(Verdict.PROGRESS, at20(1, dead(1)))                     // at least two failures, even for one bridge
        assertEquals(Verdict.STALLED, at20(1, dead(2)))
        assertEquals(Verdict.STALLED, at20(2, dead(2)))
        assertEquals(Verdict.STALLED, at20(30, dead(TorBootstrap.PROBLEM_THRESHOLD)))
        // without Tor's verdict the minute rule is unchanged
        val w = TorBootstrap.Watch(bridges = 12)
        w.observe(Phase(2, "conn_done_pt"), 0, 0)
        assertEquals(Verdict.PROGRESS, w.observe(dead(3), 59_999, 0))
        assertEquals(Verdict.STALLED, w.observe(dead(3), 60_000, 0))
    }

    @Test fun everyDaemonStartIsWatchedWithItsBridgeCount() {
        val s = source("src/main/java/com/vlesscardvpn/vpn/TunnelService.kt")
        val watch = s.substringAfter("private suspend fun watchTorBootstrap(").substringBefore("private suspend fun superviseTor(")
        assertTrue(watch.contains("var watch = com.vlesscardvpn.tor.TorBootstrap.Watch(bridges = startedLines.size)"))
        assertEquals(2, Regex(Regex.escape("watch = com.vlesscardvpn.tor.TorBootstrap.Watch(bridges = fresh.size)")).findAll(watch).count())
        assertFalse(s.contains("TorBootstrap.Watch()"))
        assertTrue(watch.contains("com.vlesscardvpn.tor.TorBridgeUpdates.resetHealth()"))
    }

    @Test fun liveBridgeStateIsCountsOnly() {
        val lines = (1..12).map { "Bridge " + obfs(it) }
        val h = TorBridgeMemory.health("obfs4", lines, mapOf(fp(1) to "up", fp(2) to "up", fp(3) to "down", fp(4) to "never-connected"))
        assertEquals(TorBridgeMemory.Health("obfs4", 2, 1, 12), h)
        assertEquals("Мосты сейчас: работают 2 из 12 · не отвечают: 1", h.text())
        assertEquals("Мосты сейчас: Tor ещё не подключился ни к одному из 12", TorBridgeMemory.Health("obfs4", 0, 0, 12).text())
        assertFalse(h.text().contains("198.51.100"))
    }

    @Test fun regularRefreshKeepsProvenBridgesFirstButStaysMostlyNew() {
        val catalog = TorBridgeCatalog.Parsed((20..40).map { "Bridge " + obfs(it) }, 0)
        val good = (1..TorBridgeRefreshPolicy.KEEP_GOOD).map { "Bridge " + obfs(it) }
        // a proven bridge that failed since is not kept; the same bridge in the catalog is not listed twice
        val r = TorBridgeRefreshPolicy.replacement(catalog, "obfs4", "old", "obfs4", "old", true, 1, 1,
            keep = good + ("Bridge " + obfs(20, mode = 1)), bad = setOf(fp(2)))!!.lines()
        assertEquals(listOf(1, 3, 4, 5, 6).map { "Bridge " + obfs(it) } + ("Bridge " + obfs(20, mode = 1)), r.take(6))
        assertEquals(12, r.size)
        assertEquals(12, r.map { TorBridgeMemory.fingerprint(it) }.distinct().size)
        assertTrue(TorBridgeRefreshPolicy.KEEP_GOOD <= 6)
        val updates = source("src/main/java/com/vlesscardvpn/tor/TorBridgeUpdates.kt")
        assertTrue(updates.contains("val kept = if (stalled && !manual) keep else remembered.good.take(TorBridgeRefreshPolicy.KEEP_GOOD)"))
        assertTrue(updates.contains("keep = kept, bad = TorBridgeMemory.bad(remembered, now))"))
    }

    @Test fun savedListSaysWhereItsBridgesCameFrom() {
        val kept = listOf("Bridge " + obfs(1))
        val official = listOf("Bridge " + obfs(2), "Bridge " + obfs(3))
        val saved = kept + official + (4..12).map { "Bridge " + obfs(it) }
        assertEquals(": уже работавших 1, Tor Project 2, igareck 9", TorBridgeRefreshPolicy.sources(saved, kept, official))
        assertEquals(": Tor Project 2, igareck 10", TorBridgeRefreshPolicy.sources(official + (4..13).map { "Bridge " + obfs(it) }, emptyList(), official))
        assertEquals("", TorBridgeRefreshPolicy.sources((4..13).map { "Bridge " + obfs(it) }, emptyList(), official))
        assertEquals(": уже работавших 1, Tor Project 2", TorBridgeRefreshPolicy.sources(kept + official, kept, official + kept))
    }
}
