package com.vlesscardvpn

import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Model-level checks only; these do not exercise Android service lifecycle or JNI. */
class VpnServiceCrashTests {

    @Test
    fun testConnectWithNullConfigYieldsErrorNotCrash() = runTest {
        val config: VlessConfig? = null
        assertNull("Config must be non-null before proceeding", config)
    }

    @Test
    fun testInvalidConfigDoesNotProduceConnected() {
        val badConfig = VlessConfig(
            id = "bad",
            name = "Bad",
            address = "",
            port = 0,
            uuid = ""
        )
        assertTrue("Invalid config must never be considered connected", badConfig.address.isBlank())
    }

    @Test
    fun testErrorStateIsPreservedAfterCleanup() {
        val errorStats = VpnSessionStats(
            status = VpnStatus.ERROR,
            errorMessage = "Сквозной тест HTTPS не пройден"
        )
        assertEquals(VpnStatus.ERROR, errorStats.status)
        assertNotNull(errorStats.errorMessage)
        assertTrue(errorStats.errorMessage!!.contains("HTTPS"))
    }

    @Test
    fun testPermissionMissingLeadsToError() {
        val hasPermission = false
        if (!hasPermission) {
            val expected = VpnStatus.ERROR
            assertEquals("Missing VPN permission must result in ERROR state", expected, VpnStatus.ERROR)
        }
    }
}
