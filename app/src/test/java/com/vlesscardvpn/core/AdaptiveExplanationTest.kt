package com.vlesscardvpn.core

import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test

class AdaptiveExplanationTest {
    private val srv = Server("private-name", "vless", "192.0.2.1", 443, "private-secret")
    private val f = Assistant.Facts("private-network", emptyMap(), true, "r", "ok", 1, 1, emptyList(), Mode.AUTO, null, false, null)
    private fun fresh(block: () -> Unit) { val old = Brain.snapshot(); try { Brain.clear(); block() } finally { assertTrue(Brain.restore(old)) } }
    @Test fun untrainedExplanationDoesNotCreateModel() = fresh {
        val before = Brain.snapshot(); val e = Brain.explain("Wi-Fi", srv, ServerState())
        assertEquals(before, Brain.snapshot()); assertEquals(0.0, e.weightedChecks, 0.0)
        assertTrue(e.score in 0.0..1.0); assertTrue(e.factors.size <= 3)
    }
    @Test fun trainedExplanationDoesNotChangeWeightsOrCount() = fresh {
        Brain.learn("Wi-Fi", srv, ServerState(tcpMs = 100), true, 0.2)
        val before = Brain.snapshot(); val e = Brain.explain("Wi-Fi", srv, ServerState(tcpMs = 100))
        assertEquals(before, Brain.snapshot()); assertEquals(0.2, e.weightedChecks, 0.000001)
        assertTrue(e.factors.all { it.value.isFinite() })
    }
    @Test fun unseenContextDoesNotBorrowFamilyObservationCount() = fresh {
        Brain.learn("Wi-Fi", srv, ServerState(), true)
        assertEquals(0.0, Brain.explain("Wi-Fi · unseen", srv, ServerState()).weightedChecks, 0.0)
    }
    @Test fun explanationDoesNotExposeEndpointNetworkOrCredentials() = fresh {
        val text = Brain.explain("private-network", srv, ServerState()).toString()
        listOf(srv.name, srv.address, srv.secret, "private-network").forEach { assertFalse(text, text.contains(it)) }
    }
    @Test fun assistantExplainsLimitsWithoutExecutingCommands() {
        listOf("как ты обучена", "улучши нейронку", "почему выбран маршрут", "можешь мыслить").forEach { q ->
            val a = Assistant.answer(q, f); assertFalse(a.auto)
            assertTrue(a.text, a.text.contains("14 признаками")); assertTrue(a.text, a.text.contains("UNKNOWN"))
            assertFalse(a.text, a.text.contains("private-network"))
        }
    }
    @Test fun assistantDoesNotClaimTorInsideVless() {
        val a = Assistant.answer("что такое tor", f)
        assertTrue(a.text, a.text.contains("не Tor внутри VLESS")); assertTrue(a.text, a.text.contains("UNKNOWN"))
    }
    @Test fun bridgesAreNotGuaranteedInvisible() {
        val a = Assistant.answer("tor мосты", f)
        assertTrue(a.text, a.text.contains("не гарантируют")); assertTrue(a.text, a.text.contains("Snowflake не является встроенным"))
    }
    @Test fun explanationClearlyLabelsSparseObservations() {
        val a = Assistant.answer("как учишься", f.copy(learning = Brain.Explanation(0.9, 0.2, listOf(Brain.Contribution("Задержка TCP", -0.1)))))
        assertTrue(a.text, a.text.contains("мало данных")); assertTrue(a.text, a.text.contains("не процент надёжности"))
    }
}
