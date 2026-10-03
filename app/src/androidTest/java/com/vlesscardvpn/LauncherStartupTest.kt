package com.vlesscardvpn

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** A saved automatic-selection preference must not turn a plain app launch into a connection. */
class LauncherStartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("vless_vpn_prefs", Context.MODE_PRIVATE)
    private val originalAutoSelect by lazy { prefs.getBoolean("autoSelect", false) }
    @After fun resetPreference() { prefs.edit().putBoolean("autoSelect", originalAutoSelect).commit() }
    @Test fun savedAutoSelectDoesNotConnectOnLauncherEntry() {
        originalAutoSelect // Save before mutation.
        assertEquals(VpnStatus.DISCONNECTED, VlessVpnService.vpnStats.value.status)
        prefs.edit().putBoolean("autoSelect", true).commit()
        compose.activityRule.scenario.recreate()
        Thread.sleep(4000)
        assertEquals("Opening the launcher must not start a connection", VpnStatus.DISCONNECTED, VlessVpnService.vpnStats.value.status)
        compose.onNodeWithText("Не подключено").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Авто").performScrollTo().assertIsDisplayed()
    }
}
