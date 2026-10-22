package com.vlesscardvpn.tor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRotationTest {
    private var t = 1_000L
    private fun clock() = t

    private fun rot() = TorRotation(rotateEveryMs = 10 * 60 * 1000, blacklistTtlMs = 15 * 60 * 1000, now = ::clock)

    @Test
    fun `record accepts samples under transport ceiling and blacklists over it`() {
        val r = rot()
        assertTrue(r.record("de", 500))
        assertFalse(r.record("de", 60_000))
        assertTrue(r.isBlacklisted("de"))
    }

    @Test
    fun `snowflake gets a doubled budget`() {
        val r = rot()
        assertTrue(r.record("de", 6_000, TorRotation.Transport.DIRECT))
        // 6_000 is fine for DIRECT (ceiling 7_000); SNOWFLAKE ceiling is 14_000
        val r2 = rot()
        assertTrue(r2.record("us", 10_000, TorRotation.Transport.SNOWFLAKE))
    }

    @Test
    fun `blacklist decays after ttl`() {
        val r = rot()
        r.blacklistCountry("jp", t)
        assertTrue(r.isBlacklisted("jp"))
        t += 15 * 60 * 1000 + 1
        assertFalse(r.isBlacklisted("jp"))
    }

    @Test
    fun `pick prefers the fastest ema`() {
        val r = rot()
        r.record("de", 100); r.record("us", 900); r.record("jp", 400)
        assertEquals("de", r.pick())
    }

    @Test
    fun `shouldRotate respects cadence and empty candidates`() {
        val r = rot()
        assertFalse(r.shouldRotate()) // no history yet
        r.record("de", 100)
        r.markRotated()
        assertFalse(r.shouldRotate()) // just rotated
        t += 10 * 60 * 1000 + 1
        assertTrue(r.shouldRotate())
    }

    @Test
    fun `json roundtrip restores history and blacklist`() {
        val r = rot()
        r.record("de", 100); r.record("us", 9_000)
        val json = r.toJson()
        val r2 = rot()
        r2.restoreJson(json)
        assertEquals(r.statsSnapshot()["history"].toString(), r2.statsSnapshot()["history"].toString())
        assertTrue(r2.isBlacklisted("us"))
    }
}
