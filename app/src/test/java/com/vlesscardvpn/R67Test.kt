package com.vlesscardvpn

import com.vlesscardvpn.core.DpiEngine
import com.vlesscardvpn.core.DpiEvo
import com.vlesscardvpn.core.DpiStrategies
import com.vlesscardvpn.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R67Test {
    @Test fun dpiEvolution() {
        val parents = DpiStrategies.BUILT_IN.filter { it.engine == DpiEngine.BYEDPI }.take(3)
        val kids = DpiEvo.breed(parents, 9, seed = 7)
        assertEquals(9, kids.size)
        assertEquals(kids, DpiEvo.breed(parents, 9, seed = 7))
        val s = Settings()
        kids.forEach { k ->
            assertTrue(k.id, k.id.startsWith(DpiEvo.PREFIX) && k.engine == DpiEngine.BYEDPI && k.own)
            assertFalse(parents.any { it.args == k.args })
            assertEquals(k, DpiStrategies.byId(k.id, s)) // lives in its id: remembered like any strategy
            // positions stay valid ByeDPI positions: numbers with optional sign / +s / +se / ':'
            k.args.zipWithNext().filter { it.first in setOf("--split", "--disorder", "--tlsrec", "--oob", "--disoob", "--fake") }
                .forEach { (_, v) -> assertTrue(v, Regex("^-?\\d+([:+]-?\\d*[a-z]*)*$").matches(v) || v.contains(':')) }
            k.args.zipWithNext().filter { it.first == "--ttl" }.forEach { (_, v) -> assertTrue(v.toInt() in 3..12) }
        }
        assertTrue(DpiEvo.breed(DpiStrategies.BUILT_IN.filter { it.engine == DpiEngine.XRAY }, 3).isEmpty())
    }

    @Test fun rankingIsRememberedAndPlanned() {
        val evo = DpiEvo.breed(listOf(DpiStrategies.CASCADE), 1, seed = 3).first()
        val s = Settings(dpiRanking = mapOf("wifi:home" to listOf(evo.id, "BYEDPI#DISORDER")), dpiRemembered = mapOf("wifi:home" to evo.id))
        val r = Settings.fromJson(s.toJson())
        assertEquals(s.dpiRanking, r.dpiRanking)
        assertEquals(evo.id, DpiStrategies.resolve(r, "wifi:home").id)
        val plan = DpiStrategies.plan(r, "wifi:home", tpwsAvailable = false)
        assertTrue(plan.indexOfFirst { it.id == evo.id } <= 1) // right after the custom line
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
    }
}
