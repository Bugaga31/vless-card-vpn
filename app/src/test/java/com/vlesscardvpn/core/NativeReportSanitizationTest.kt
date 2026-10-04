package com.vlesscardvpn.core

import com.vlesscardvpn.VlessApplication
import org.junit.Assert.*
import org.junit.Test

class NativeReportSanitizationTest {
    @Test fun abortMessageQueryCredentialsAndSniAreRemoved() {
        val text = VlessApplication.sanitizeLog("panic: sni=private.example.org&token=mytoken&pbk=mykey")
        assertFalse(text.contains("private.example.org"))
        assertFalse(text.contains("mytoken"))
        assertFalse(text.contains("mykey"))
        assertTrue(text.contains("panic:"))
    }
    @Test fun jsonEndpointAndCredentialFieldsAreRemoved() {
        val text = VlessApplication.sanitizeLog("""{"server_name":"secret.example.org","address":"10.0.0.1","password":"mypass"}""")
        assertFalse(text.contains("secret.example.org"))
        assertFalse(text.contains("10.0.0.1"))
        assertFalse(text.contains("mypass"))
    }
    @Test fun completeConfigUrisAreRemoved() {
        val text = VlessApplication.sanitizeLog("abort: vless://private-user@secret.example.org:443?sni=private.example.org")
        assertFalse(text.contains("private-user"))
        assertFalse(text.contains("secret.example.org"))
        assertTrue(text.contains("abort:"))
    }
}
