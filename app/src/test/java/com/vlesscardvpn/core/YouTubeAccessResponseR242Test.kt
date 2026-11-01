package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class YouTubeAccessResponseR242Test {
    @Test fun onlyExpected204Passes() {
        for(code in 100..599) assertEquals(code == 204, YouTubeAccessResponsePolicy.evaluate(code, true).ok == true)
    }
    @Test fun redirectCannotPassEvenWith204() {
        assertNull(YouTubeAccessResponsePolicy.evaluate(204, false).ok)
        assertEquals("YT_ACCESS_REDIRECT", YouTubeAccessResponsePolicy.evaluate(302, true).reason)
    }
    @Test fun serviceRestrictionsRemainUnknownNotPass() {
        for(code in listOf(403,451,429,500,502,503,599)) assertNull(YouTubeAccessResponsePolicy.evaluate(code,true).ok)
        assertEquals("YT_RATE_LIMITED",YouTubeAccessResponsePolicy.evaluate(429,true).reason)
        assertEquals("YT_ACCESS_RESTRICTED",YouTubeAccessResponsePolicy.evaluate(403,true).reason)
        assertEquals("YT_SERVICE_UNAVAILABLE",YouTubeAccessResponsePolicy.evaluate(503,true).reason)
    }
    @Test fun htmlAndMissingEndpointAreNotProof() {
        assertEquals(false,YouTubeAccessResponsePolicy.evaluate(200,true).ok)
        assertEquals(false,YouTubeAccessResponsePolicy.evaluate(404,true).ok)
    }
}
