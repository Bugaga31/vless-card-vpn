package com.vlesscardvpn

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
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
    @After fun resetPreference() { prefs.edit().putBoolean("autoSelect", false).commit() }
    @Test fun savedAutoSelectDoesNotConnectOnLauncherEntry() {
        prefs.edit().putBoolean("autoSelect", true).commit()
        compose.activityRule.scenario.recreate()
        Thread.sleep(4000)
        compose.onNodeWithText("Не подключено").assertIsDisplayed()
        compose.onNodeWithText("Авто").assertIsDisplayed()
    }
}
