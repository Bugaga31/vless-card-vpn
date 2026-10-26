package com.vlesscardvpn.neural

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Mode
import org.junit.Assert.*
import org.junit.Test

class AssistantWebSearchTest {
    private val facts = Assistant.Facts("PRIVATE_NETWORK", emptyMap(), false, "PRIVATE_ROUTE", "UNKNOWN", 0, 0, emptyList(), Mode.AUTO, null, false, null)
    @Test fun searchRequiresExplicitQueryAndClick() {
        assertNull(AssistantWebSearch.query("как работает Tor"))
        val answer = Assistant.answer("найди в интернете как работает Tor", facts)
        assertFalse(answer.auto); assertEquals(1, answer.buttons.size)
        assertEquals("!web как работает Tor", answer.buttons.single().cmd)
        assertFalse(answer.text.contains("PRIVATE_"))
    }
    @Test fun noSecretsOrConfigurationsInSearchUrls() {
        for (q in listOf("vless://secret@host", "password=secret", "private_key: abc", "token=123", "-----BEGIN PRIVATE KEY", "a".repeat(301), "x\ny", "a".repeat(40))) {
            assertNull(q, AssistantWebSearch.url(q))
        }
    }
    @Test fun fixedHttpsProviderAndEncodedQuery() {
        assertEquals("https://duckduckgo.com/?q=Tor+%26+DNS", AssistantWebSearch.url("Tor & DNS"))
        assertNull(AssistantWebSearch.query("найди в интернете "))
    }
    @Test fun neuralExplanationDoesNotExecuteOrPromiseHumanThinking() {
        val a = Assistant.answer("включи нейронку", facts)
        assertFalse(a.auto); assertTrue(a.text.contains("не разговорная")); assertTrue(a.buttons.any { it.cmd == "!neural on" })
    }
}
