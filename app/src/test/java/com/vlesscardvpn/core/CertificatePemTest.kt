package com.vlesscardvpn.core
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import org.junit.Assert.*
import org.junit.Test

class CertificatePemTest {
    @Test fun pemRoundTripsThroughStandardX509Parser() {
        val base64 = "MIIDHzCCAgegAwIBAgIUJ2USID9GccpnUL8lyyJ4H0/ousQwDQYJKoZIhvcNAQELBQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MTAwMTE4MDczN1oXDTI2MTAwMjE4MDczN1owFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtt9cQn4iOcfkFTui+vrQoI/7SdLUHqo3ubzHHZshpbtXvr7p96hmczl/3n4Wf8iOSU4eZVG5RQ1mltTVTZy5mJDNt5cAr4oKXDDOSjUteqcB253+eapSc6/mPTN8kIhCQU4ggUhP6M46Bee/ySBHAt8R+5g/HFpOWTk1MfGSQ7X+4Sar7e9FAwEUczFDRfiVIHjgdbXfhVFL4bWOfYNUsX8M3UCG2Bn/IrtpRWiD66XzV3eIBAf8YnbVnZBJt/09WoLiSCH+0x1ujuzUnCxVeFHKv2LqrWgaDYb2dTKIqAgBFW1t3wS6hQvfn6/sHvjIxbnQ203OXOB2WmDUOG/KVQIDAQABo2kwZzAdBgNVHQ4EFgQUlcTcjZlss97WgfCBPFGNn5TTrUkwHwYDVR0jBBgwFoAUlcTcjZlss97WgfCBPFGNn5TTrUkwDwYDVR0TAQH/BAUwAwEB/zAUBgNVHREEDTALgglsb2NhbGhvc3QwDQYJKoZIhvcNAQELBQADggEBAIqrIDTf+ab6kbbE8Vrdqn4t17u/ltr4/DXNUTzZU6iwhXjHiPvgya+c0yihYYeKnZIfNXtivdOtQ4goRT85r0uR5ePpQpIB80GoOaDLK6p6eFMWkIvCSh1nrstoACyxH9YV2YM7o47uuf9IRaHf/43xxqTx8+09ocwdvtVagHmy/kzUt9qXkRT/J+NvOEFB02FOsGpo1xd0SVqx4H5vSzDjXksKy/06SNizY8oCzTDR3/hp+vjqvVHm7H7E5CTaOH6c0OMCTiPrr5l0ofxKWootTHK8lQJh72ZwYpaosoD0v8dLD6it8Xlm7BI1L8aQ32Sq8raKkt+0Xz2glxZK67M="
        val pem = CertificatePem.wrap(base64)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        assertNotNull(CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())))
    }
    @Test(expected = IllegalArgumentException::class) fun arbitraryTextCannotBecomeTrustAnchor() { CertificatePem.wrap("not a certificate") }
}
