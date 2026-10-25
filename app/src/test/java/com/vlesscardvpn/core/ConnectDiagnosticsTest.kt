package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test

class ConnectDiagnosticsTest {
    @Test fun keepsSafeErrorTypeAndSourceLocation() {
        val e = IllegalStateException("token=SECRET https://private.example/ 203.0.113.7")
        e.stackTrace = arrayOf(StackTraceElement("com.vlesscardvpn.vpn.TunnelService", "connect", "TunnelService.kt", 123))
        val text = ConnectDiagnostics.describe("connect","tun",e)
        assertTrue(text.contains("type=IllegalStateException")); assertTrue(text.contains("origin=TunnelService.kt:123"))
        listOf("SECRET","private.example","203.0.113.7","token=").forEach { assertFalse(text.contains(it)) }
    }
    @Test fun classifiesFileLimitWithoutPrintingTheMessage() {
        val text = ConnectDiagnostics.describe("connect","core",RuntimeException("private", java.io.IOException("Too many open files /private/key")))
        assertTrue(text.contains("code=file_limit")); assertTrue(text.contains("causes=RuntimeException,IOException"));assertFalse(text.contains("private"))
    }
    @Test fun unknownScopeAndPhaseCannotLeakUserInput() {
        val text = ConnectDiagnostics.describe("SECRET", "https://private.example", Exception("private"))
        assertTrue(text.contains("scope=connect phase=init")); assertFalse(text.contains("SECRET"));assertFalse(text.contains("private"))
    }
    @Test fun unknownStackFileAndClassAreNotPrinted() {
        val e = IllegalArgumentException("private")
        e.stackTrace = arrayOf(StackTraceElement("com.vlesscardvpn.SECRET", "SECRET", "SECRET.kt", 5))
        assertTrue(ConnectDiagnostics.describe("action","action",e).contains("origin=unknown"))
    }
    @Test fun causeDepthIsBounded() {
        var e: Throwable = Exception("private")
        repeat(20) { e = RuntimeException("private",e) }
        assertEquals(5, ConnectDiagnostics.describe("connect","core",e).substringAfter("causes=").split(',').size)
    }
    @Test fun fixedChainErrorsAreDistinguished() {
        assertTrue(ConnectDiagnostics.describe("chain","chain_check",IllegalStateException("No running DPI set")).contains("code=dpi_missing"))
        assertTrue(ConnectDiagnostics.describe("chain","chain_check",IllegalStateException("No verified DPI-server winner")).contains("code=chain_unconfirmed"))
        assertTrue(ConnectDiagnostics.describe("chain","chain_check",IllegalStateException("Measured chain was not applied")).contains("code=chain_not_applied"))
    }
}
