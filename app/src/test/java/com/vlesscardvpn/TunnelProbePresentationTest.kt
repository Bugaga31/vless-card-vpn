package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import org.junit.Assert.*
import org.junit.Test

class TunnelProbePresentationTest {
    @Test fun untestedAndMissingResultsAreNotFailuresOrSuccesses() {
        assertEquals("Не проверено", TunnelProbePresentation.status(null, false))
        assertEquals("Нет результата", TunnelProbePresentation.status(null, true))
    }
    @Test fun realSuccessHasNoArtificialError() {
        assertEquals("Доступен", TunnelProbePresentation.status(TunnelProbe("Cloudflare", 12, 204), true))
        assertFalse(TunnelProbe("Cloudflare", 12, 204, failure = DiagnosticFailure.TIMEOUT).passed)
    }
    @Test fun timeoutRetainsItsActualStage() {
        for ((stage, text) in listOf(DiagnosticFailure.CONNECT to "Таймаут порта", DiagnosticFailure.PROXY to "Таймаут SOCKS",
            DiagnosticFailure.TLS to "Таймаут TLS", DiagnosticFailure.HTTP to "Таймаут HTTP")) {
            assertEquals(text, TunnelProbePresentation.status(TunnelProbe("YouTube", failure = DiagnosticFailure.TIMEOUT, stage = stage), true))
        }
    }
    @Test fun httpFailurePreservesCodeWithoutClaimingSiteWorks() {
        val probe = TunnelProbe("Cloudflare", 20, 403, failure = DiagnosticFailure.HTTP, stage = DiagnosticFailure.HTTP)
        assertFalse(probe.passed)
        assertEquals("HTTP 403", TunnelProbePresentation.status(probe, true))
        assertEquals("Ответ некорректен", TunnelProbePresentation.status(probe.copy(httpCode = -1), true))
    }
    @Test fun labelsCannotExposeRawHostOrKey() {
        for (failure in DiagnosticFailure.entries) {
            val text = TunnelProbePresentation.status(TunnelProbe("secret-host-and-token", failure = failure, stage = failure), true)
            assertFalse(text.contains("secret"))
        }
    }
}
