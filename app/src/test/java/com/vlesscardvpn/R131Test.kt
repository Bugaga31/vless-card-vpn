package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.CloudSpeed
import com.vlesscardvpn.core.Report
import com.vlesscardvpn.core.ServerChoice
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.junit.Assert.*
import org.junit.Test

class R131Test {
    private fun srv(host: String, port: Int = 443) = Server(name = host, protocol = "vless", address = host, port = port, secret = "u")

    @Test fun probeBudgetDoesNotGoToOneHost() {
        val a = srv("one.example"); val a2 = srv("one.example", 8443); val a3 = srv("ONE.EXAMPLE.", 9443)
        val b = srv("two.example"); val c = srv("three.example")
        assertEquals(listOf(a, b, c), ServerChoice.diverse(listOf(a, a2, a3, b, c), 3))
        assertEquals(listOf(a, b, c, a2), ServerChoice.diverse(listOf(a, a2, a3, b, c), 4))
        assertEquals(listOf(a, a2), ServerChoice.diverse(listOf(a, a, a2), 5))
        assertTrue(ServerChoice.diverse(listOf(a), 0).isEmpty())
        assertTrue(ServerChoice.diverse(listOf(a), -1).isEmpty())
    }

    @Test fun cloudFastCandidatesAreAlsoDiverse() {
        val a = srv("1.2.3.4"); val a2 = srv("1.2.3.4", 8443); val b = srv("2.3.4.5")
        val sp = mapOf(Report.serverKey(a) to 10000, Report.serverKey(a2) to 9000, Report.serverKey(b) to 8000)
        assertEquals(listOf(a, b), CloudSpeed.pick(listOf(a, a2, b), emptyList(), n = 2, speeds = sp, here = emptyMap()))
        assertEquals(listOf(a2, b), CloudSpeed.pick(listOf(a, a2, b), listOf(a), n = 2, speeds = sp, here = emptyMap()))
    }

    @Test fun aCoupleFailuresDoNotBlacklistAnOperatorServer() {
        val s = srv("1.2.3.4"); val k = Report.serverKey(s)
        (0..4).forEach { n -> assertFalse(CloudSpeed.blocked(s, mapOf(k to intArrayOf(0, 0, n)))) }
        assertTrue(CloudSpeed.blocked(s, mapOf(k to intArrayOf(0, 0, 5))))
        assertFalse(CloudSpeed.blocked(s, mapOf(k to intArrayOf(5000, 80, 40))))
        assertFalse(CloudSpeed.blocked(s, mapOf(k to intArrayOf(0, 20, 5))))
        assertTrue(CloudSpeed.blocked(s, mapOf(k to intArrayOf(0, 20, 40))))
        assertFalse(CloudSpeed.blocked(s, mapOf(k to intArrayOf(0))))
    }

    @Test fun operatorOpinionGetsStrongerWithEvidenceButStaysBounded() {
        fun factor(n: Int, ok: Int) = Actions.operatorFactor("a", mapOf("a" to intArrayOf(0, ok, n)))
        assertEquals(1.0, factor(1, 0), 1e-9)
        assertTrue(factor(2, 0) < factor(20, 0))
        assertTrue(factor(2, 90) > factor(20, 90))
        assertTrue(factor(Int.MAX_VALUE, 0) in 1.0..2.0)
        assertTrue(factor(Int.MAX_VALUE, 100) in 0.85..1.0)
        assertEquals(0.5, ServerChoice.successChance(0, 0), 1e-9)
        assertEquals(1.0, Actions.operatorFactor("a", mapOf("a" to intArrayOf(0))), 1e-9)
    }

    @Test fun workingSelectionHasHysteresisButNeverKeepsADeadServer() {
        val oldNet = CloudSpeed.net; val oldLoad = Report.load
        try {
            CloudSpeed.net = emptyMap(); Report.load = emptyMap()
            val s = srv("a.example")
            val current = ServerState(realMs = 200, bigOk = true, kbps = 10000, selected = true)
            val score = Actions.spreadScore(current, s, "phone")
            (195..205).forEach { ms -> assertTrue(score < Actions.spreadScore(current.copy(realMs = ms, selected = false), s, "phone")) }
            assertTrue(Actions.spreadScore(current.copy(realMs = 100, selected = false), s, "phone") < score)
            assertEquals(Int.MAX_VALUE, Actions.spreadScore(current.copy(realMs = 0), s, "phone"))
            assertEquals(Int.MAX_VALUE, Actions.spreadScore(current.copy(bigOk = false), s, "phone"))
        } finally { CloudSpeed.net = oldNet; Report.load = oldLoad }
    }

    @Test fun unusableVideoSpeedCannotDisqualifyHealthyVideoServers() {
        assertEquals(setOf(1, 2), XrayConfigBuilder.heavyOf(listOf(1000000, 10000, 8000), listOf(false, true, true)))
        assertEquals(setOf(0, 1), XrayConfigBuilder.heavyOf(listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1), listOf(true, true)))
        assertTrue(XrayConfigBuilder.heavyOf(listOf(5000, 9000), listOf(false, false)).isEmpty())
        assertTrue(XrayConfigBuilder.heavyOf(listOf(-1, 0), listOf(true, true)).isEmpty())
        assertEquals(setOf(1), XrayConfigBuilder.heavyOf(listOf(0, 8000), emptyList()))
    }
}
