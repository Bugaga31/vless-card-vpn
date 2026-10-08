package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.AppState
import com.vlesscardvpn.core.Backup
import com.vlesscardvpn.core.Tester
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R73Test {
    private val srv = Server("c", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "a.com", source = "manual")

    @Test fun hostNormalize() {
        assertEquals("rutracker.org", Settings.host(" https://WWW.RuTracker.org/forum/index.php?x=1 "))
        assertEquals("kinopoisk.ru", Settings.host("kinopoisk.ru:443"))
        assertEquals("госуслуги.рф", Settings.host("госуслуги.рф"))
        assertNull(Settings.host("привет")); assertNull(Settings.host(""))
    }

    @Test fun siteRoutingRules() {
        val s = Settings(alwaysVpn = listOf("rutracker.org"), alwaysDirect = listOf("sber.ru"))
        val cfg = JSONObject(XrayConfigBuilder.vpnConfig(listOf(srv to null), s, null))
        val rules = cfg.getJSONObject("routing").getJSONArray("rules")
        val idx = (0 until rules.length()).map { rules.getJSONObject(it).toString() }
        val d = idx.indexOfFirst { it.contains("domain:sber.ru") }; val v = idx.indexOfFirst { it.contains("domain:rutracker.org") }
        val ads = idx.indexOfFirst { it.contains("category-ads-all") }
        assertTrue(d >= 0 && v >= 0 && d < ads && v < ads)
        assertEquals("direct", rules.getJSONObject(d).getString("outboundTag"))
        val back = Settings.fromJson(s.toJson()); assertEquals(s.alwaysVpn, back.alwaysVpn); assertEquals(s.alwaysDirect, back.alwaysDirect)
        java.io.File(System.getenv("XRAY_CONFIG_DUMP") ?: return).apply { mkdirs() }.resolve("sites.json").writeText(cfg.toString())
    }

    @Test fun verdicts() {
        val ok = Tester.SiteProbe("ok", 120); val hang = Tester.SiteProbe("timeout")
        assertEquals("vpn", Actions.siteVerdict("x.com", hang, ok, null).suggest)
        assertEquals("direct", Actions.siteVerdict("x.ru", ok, Tester.SiteProbe("reset"), null).suggest)
        assertTrue(Actions.siteVerdict("x.ru", hang, hang, ok).verdict.contains("обход DPI"))
        assertTrue(Actions.siteVerdict("x.ru", Tester.SiteProbe("dns"), Tester.SiteProbe("dns"), null).verdict.contains("нет"))
        assertTrue(Actions.siteVerdict("x.ru", hang, null, null).verdict.contains("подключитесь"))
    }

    @Test fun backupRoundTrip() {
        val sub = srv.copy(address = "5.6.7.8", source = "https://sub.example/x")
        val st = AppState(servers = listOf(srv, sub), settings = Settings(alwaysVpn = listOf("a.org"), turbo = false))
        val link = Backup.pack(st)
        assertTrue(link.startsWith("vcbackup://") && !link.contains('+') && link.indexOf('/', 11) < 0)
        val body = Backup.find("Резервная копия:\n$link\n")
        assertNotNull(body)
        val back = Backup.unpack(body!!)!!
        assertEquals(listOf(srv.id), back.servers.map { it.id })
        assertEquals(listOf("a.org"), back.settings.alwaysVpn); assertEquals(false, back.settings.turbo)
        val (merged, n) = Backup.merge(AppState(), back)
        assertEquals(1, n); assertEquals(listOf("a.org"), merged.settings.alwaysVpn)
        assertEquals(0, Backup.merge(merged, back).second)
        assertNull(Backup.unpack("AAAA"))
    }
}
