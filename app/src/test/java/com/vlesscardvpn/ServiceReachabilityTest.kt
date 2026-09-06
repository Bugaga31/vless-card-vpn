package com.vlesscardvpn

import com.vlesscardvpn.domain.ServiceProbe
import com.vlesscardvpn.domain.ServiceReachabilityResult
import com.vlesscardvpn.domain.ServiceTarget
import org.junit.Assert.*
import org.junit.Test

class ServiceReachabilityTest {
    @Test fun medianDoesNotPickOnlyFastestProbe() {
        val result = ServiceReachabilityResult(ServiceTarget.YOUTUBE,
            listOf(300, 10, 80).map { ServiceProbe(it, 204) })
        assertEquals(80, result.medianMs)
    }
    @Test fun wrongStatusCannotCountAsYoutubeSuccess() {
        val result = ServiceReachabilityResult(ServiceTarget.YOUTUBE,
            listOf(ServiceProbe(10, 200), ServiceProbe(20, 302), ServiceProbe(30, 403)))
        assertEquals(0, result.successful.size)
        assertNull(result.medianMs)
    }
    @Test fun partialResultsRemainPartial() {
        val result = ServiceReachabilityResult(ServiceTarget.TELEGRAM,
            listOf(ServiceProbe(40, 200), ServiceProbe(error = "timeout"), ServiceProbe(80, 200)))
        assertEquals(60, result.medianMs)
        assertEquals("Частичный ответ: 2/3", result.summary)
    }
    @Test fun failureIsNotZeroLatency() {
        val result = ServiceReachabilityResult(ServiceTarget.TELEGRAM, listOf(ServiceProbe(error = "timeout")))
        assertNull(result.medianMs)
        assertEquals("Ожидаемый ответ не получен", result.summary)
    }
    @Test fun emptyResultsAreNotSuccessful() {
        val result = ServiceReachabilityResult(ServiceTarget.YOUTUBE, emptyList())
        assertNull(result.medianMs)
        assertEquals("Ожидаемый ответ не получен", result.summary)
    }
    @Test fun errorOrMissingTimingCannotBeSuccess() {
        val result = ServiceReachabilityResult(ServiceTarget.TELEGRAM,
            listOf(ServiceProbe(10, 200, "cancelled"), ServiceProbe(httpCode = 200)))
        assertTrue(result.successful.isEmpty())
    }
    @Test fun serviceEndpointsHaveNoGoogleFallback() {
        assertEquals("https://www.youtube.com/generate_204", ServiceTarget.YOUTUBE.url)
        assertEquals("https://telegram.org/", ServiceTarget.TELEGRAM.url)
        assertEquals(2, ServiceTarget.values().size)
    }
}
