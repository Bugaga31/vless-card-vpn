package com.vlesscardvpn

import com.vlesscardvpn.core.AntiDpiEvasionEngine
import org.junit.Assert.*
import org.junit.Test

class AntiDpiEvasionEngineTest {

    @Test
    fun testRussianWhitelistDomainsNotEmpty() {
        val list = AntiDpiEvasionEngine.RUSSIAN_WHITELIST_DOMAINS
        assertTrue(list.isNotEmpty())
        assertTrue(list.contains("yandex.ru"))
        assertTrue(list.contains("vk.com"))
        assertTrue(list.contains("gosuslugi.ru"))
        assertTrue(list.contains("sberbank.ru"))
    }

    @Test
    fun testSelectStealthSniOperatorHint() {
        assertEquals("mts.ru", AntiDpiEvasionEngine.selectStealthSni("mts"))
        assertEquals("beeline.ru", AntiDpiEvasionEngine.selectStealthSni("beeline"))
        assertEquals("megafon.ru", AntiDpiEvasionEngine.selectStealthSni("megafon"))
        assertEquals("tele2.ru", AntiDpiEvasionEngine.selectStealthSni("tele2"))
        
        val randomSni = AntiDpiEvasionEngine.selectStealthSni(null)
        assertTrue(AntiDpiEvasionEngine.RUSSIAN_WHITELIST_DOMAINS.contains(randomSni))
    }

    @Test
    fun testHttpFakeHeaders() {
        val headers = AntiDpiEvasionEngine.generateHttpFakeHeaders("yandex.ru")
        assertEquals("yandex.ru", headers["Host"])
        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(headers["User-Agent"]!!.contains("Chrome"))
        assertTrue(headers.containsKey("Accept-Language"))
    }
}
