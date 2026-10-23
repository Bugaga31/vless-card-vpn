package com.vlesscardvpn.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.43: «Отдельный путь Tor» — флаг сохраняется в JSON-профиле. */
class TorDirectR155Test {
    @Test
    fun torDirectRoundTripsThroughJson() {
        val rt = Settings(torRouting = true, torDirect = true).toJson().let { Settings.fromJson(it) }
        assertTrue("torDirect must persist", rt.torDirect)
        assertEquals(true, rt.torRouting)
        val off = Settings.fromJson(Settings(torRouting = true).toJson())
        assertEquals(false, off.torDirect)
    }
}
