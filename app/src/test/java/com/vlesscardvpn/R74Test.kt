package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Tester
import com.vlesscardvpn.core.Traffic
import com.vlesscardvpn.model.Base64
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.SubInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R74Test {
    @Test fun subscriptionInfo() {
        val i = SubInfo.parse("upload=1073741824; download=3221225472; total=53687091200; expire=1900000000", "base64:" + Base64.encode("Мой VPN"), 5)!!
        assertEquals(4L shl 30, i.used); assertEquals(46L shl 30, i.left); assertEquals("Мой VPN", i.title); assertEquals(1900000000L, i.expire)
        assertFalse(i.ending(1800000000)); assertTrue(i.ending(1899990000))
        assertTrue(SubInfo(used = 95, total = 100).ending(0))
        assertNull(SubInfo.parse(null, null)); assertEquals("Free", SubInfo.parse(null, "Free")!!.title)
        val s = Settings(subInfo = mapOf("https://a/b" to i), traffic = mapOf("2025-10" to 5L), monthLimitGb = 30)
        val back = Settings.fromJson(s.toJson())
        assertEquals(i, back.subInfo["https://a/b"]); assertEquals(5L, back.traffic["2025-10"]); assertEquals(30, back.monthLimitGb)
        val line = Actions.subLine(i, 1800000000)
        assertTrue(line, line.contains("осталось") && line.contains("2030"))
    }

    @Test fun monthlyTraffic() {
        var s = Settings()
        for (m in listOf("2025-07", "2025-08", "2025-09", "2025-10")) s = Traffic.add(s, 10, m)
        s = Traffic.add(s, 5, "2025-10")
        assertEquals(listOf("2025-08", "2025-09", "2025-10"), s.traffic.keys.sorted()); assertEquals(15L, s.traffic["2025-10"])
        val now = Traffic.add(Settings(monthLimitGb = 1), 1000L shl 20, Traffic.month())
        val (text, warn) = Traffic.line(now)
        assertTrue(text, warn && text.contains("из 1 ГБ"))
        assertEquals("", Traffic.line(Settings()).first)
    }

    @Test fun deniedVerdicts() {
        val ok = Tester.SiteProbe("ok", 100); val denied = Tester.SiteProbe("denied", 100, 403)
        assertEquals("direct", Actions.siteVerdict("bank.ru", ok, denied, null).suggest)
        val c = Actions.siteVerdict("chatgpt.com", denied, ok, null); assertEquals("vpn", c.suggest); assertTrue(c.good)
        assertFalse(Actions.siteVerdict("chatgpt.com", denied, denied, null).good)
        val rows = listOf("A" to Actions.siteVerdict("a", ok, ok, null), "B" to Actions.siteVerdict("b", denied, denied, null))
        assertTrue(Actions.popularSummary(rows, true).contains("B"))
        assertTrue(Actions.popularSummary(rows.take(1), true).startsWith("Через VPN работает всё"))
    }
}
