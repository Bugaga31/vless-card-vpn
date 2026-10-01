package com.vlesscardvpn

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.ui.*
import com.vlesscardvpn.ui.components.*
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.core.CrashReportManager
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*

class ScreenDesignSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:style/Theme.Material.NoActionBar")
    private val server = VlessConfig(name = "Личный сервер", address = "vpn.example.org", port = 443, uuid = "fixture", isActive = true, isFavorite = true, pingMs = 120)
    private val repo = mockk<AppRepository>(relaxed = true)
    private val pilot = mockk<AutoPilotEngine>(relaxed = true)
    @Before fun setup() {
        every { repo.settingsFlow } returns MutableStateFlow(AppSettings(themeMode = "system"))
        every { repo.configsFlow } returns MutableStateFlow(listOf(server, server.copy(id = "second", name = "Запасной сервер", isActive = false, isFavorite = false)))
        every { pilot.state } returns MutableStateFlow(NetworkAutopilotState())
        mockkObject(CrashReportManager)
        every { CrashReportManager.getSavedCrashes(any()) } returns emptyList()
    }
    @After fun release() { unmockkObject(CrashReportManager) }
    @Test fun serversLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { ServersScreen(repo, {}, {}, {}) } } }
    @Test fun serversDark() { paparazzi.snapshot { VlessCardVpnTheme(true) { ServersScreen(repo, {}, {}, {}) } } }
    @Test fun serversEmptyLight() {
        every { repo.configsFlow } returns MutableStateFlow(emptyList())
        paparazzi.snapshot { VlessCardVpnTheme(false) { ServersScreen(repo, {}, {}, {}) } }
    }
    @Test fun settingsLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { SettingsScreen(repo, pilot, onBack = {}) } } }
    @Test fun settingsDark() { paparazzi.snapshot { VlessCardVpnTheme(true) { SettingsScreen(repo, pilot, onBack = {}) } } }
    @Test fun diagnosticsLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { DiagnosticScreen(repo, {}) } } }
    @Test fun freeSourcesLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { FreeConfigsScreen(repo, {}) } } }
    @Test fun reportsLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { CrashReportsScreen(repo, {}) } } }
    @Test fun autoPilotLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { AutopilotScreen(repo, pilot, {}) } } }
    @Test fun modesLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { StealthProfileScreen(repo, {}) } } }
    @Test fun splashDark() { paparazzi.snapshot { VlessCardVpnTheme(true) { InstrumentSplashScreen() } } }
    @Test fun appearanceLargeText() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().padding(20.dp)) { AppearancePanel("light", {}) }
            }
        }
    } } }
    @Test fun serverLongNameLargeText() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().padding(20.dp)) {
                    ServerCard(server.copy(name = "Очень длинное название личного сервера из подписки", isActive = false),
                        onSelect = {}, onPing = {}, onDelete = {})
                }
            }
        }
    } } }
    @Test fun deleteConfirmationLight() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        ServerDeleteConfirmation("Личный сервер", {}, {})
    } } }
    @Test fun settingsBottomLight() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        SettingsScreen(repo, pilot, scrollState = androidx.compose.foundation.ScrollState(10000), onBack = {})
    } } }

}
