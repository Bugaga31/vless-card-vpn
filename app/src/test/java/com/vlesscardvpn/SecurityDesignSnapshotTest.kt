package com.vlesscardvpn
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.ui.*
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.worker.*
import org.junit.Rule
import org.junit.Test

class SecurityDesignSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:style/Theme.Material.NoActionBar")
    private val issue = "Ключ или защищённая запись недоступны. Данные не удалены. Не переустанавливайте приложение без резервной копии."
    @Test fun storageLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { Surface { Box(Modifier.fillMaxSize().padding(20.dp)) { StorageProtectionPanel() } } } } }
    @Test fun storageDark() { paparazzi.snapshot { VlessCardVpnTheme(true) { Surface { Box(Modifier.fillMaxSize().padding(20.dp)) { StorageProtectionPanel() } } } } }
    @Test fun storageLockedLargeText() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            Surface { Box(Modifier.fillMaxSize().padding(20.dp)) { StorageProtectionPanel(issue) } }
        }
    } } }
    @Test fun homeLocked() { paparazzi.snapshot { VlessCardVpnTheme(false) { HomeDashboard(VpnSessionStats(), storageIssue = issue) } } }
    @Test fun partialDarkLargeText() { paparazzi.snapshot { VlessCardVpnTheme(true) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTED, profileLabel = "VPN + ByeDPI",
                health = TunnelHealthReport(listOf(TunnelProbe("Cloudflare", 120, 204)), 1)), selected = VlessConfig(name = "Сервер подписки", address = "vpn.example.org", port = 443, uuid = "fixture", security = "tls", flow = ""), configCount = 12)
        }
    } } }
}
