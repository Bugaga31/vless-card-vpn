package com.vlesscardvpn
import com.vlesscardvpn.domain.SubscriptionPolicy
import com.vlesscardvpn.util.UniversalConfigParser
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SubscriptionPolicyTest {
    @Test fun onlyPublicHttpsWithoutEmbeddedCredentialsIsAllowed() {
        assertEquals("https://example.org/sub?token=private", SubscriptionPolicy.url("https://example.org/sub?token=private"))
        assertNotNull(SubscriptionPolicy.url("https://example.org:8443/sub"))
        listOf("http://example.org/sub", "https://user:password@example.org", "https://127.0.0.1/sub", "https://192.168.1.1/",
            "https://localhost", "https://test.local", "https://example.org:0", "https://example.org/#secret").forEach { assertNull(it, SubscriptionPolicy.url(it)) }
    }
    @Test fun dnsCannotReturnPrivateOrLocalAddresses() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.0.1", "169.254.1.1", "::1", "fd00::1").forEach {
            assertFalse(it, SubscriptionPolicy.publicAddress(InetAddress.getByName(it)))
        }
        assertTrue(SubscriptionPolicy.publicAddress(InetAddress.getByName("1.1.1.1")))
    }
    @Test fun multipleSubscriptionsAreBoundedAndDeduplicated() {
        val input = (1..20).joinToString("\n") { "https://example.org/$it" }
        assertEquals(12, SubscriptionPolicy.urls(input).size)
        assertEquals(1, SubscriptionPolicy.urls("https://example.org/sub\nhttps://example.org/sub").size)
    }
    @Test(expected = IllegalArgumentException::class) fun largeImportIsRejected() { UniversalConfigParser.parseAny("x".repeat(2 * 1024 * 1024 + 1)) }
    @Test fun bomTextIsParsed() {
        assertEquals(1, UniversalConfigParser.parseAny("\uFEFFvless://00000000-0000-4000-8000-000000000001@vpn.example.org:443?security=tls&sni=vpn.example.org&type=tcp#Test").size)
    }
}
