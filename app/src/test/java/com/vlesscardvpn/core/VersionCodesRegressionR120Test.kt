package com.vlesscardvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionCodesRegressionR120Test {
    @Test fun versionCodes12() {
        val u = com.vlesscardvpn.core.Updater
        assertEquals(111, u.codeOf("v1.2.0"))
        assertEquals(115, u.codeOf("v1.2.4"))
        assertEquals(110, u.codeOf("v1.1.9"))
        assertEquals("VLESS-Card-Beta-1.2.0-arm64.apk", u.apkName("v1.2.0", true))
    }
}
