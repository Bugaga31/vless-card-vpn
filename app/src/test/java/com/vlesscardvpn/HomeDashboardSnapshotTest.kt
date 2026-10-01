package com.vlesscardvpn

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.ui.HomeDashboard
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.worker.*
import org.junit.Rule
import org.junit.Test

class HomeDashboardSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:style/Theme.Material.NoActionBar")
    private val server = VlessConfig(name = "Избранный сервер · Reality", address = "vpn.example.org", port = 443, uuid = "test")
    @Test fun disconnected() { paparazzi.snapshot { VlessCardVpnTheme { HomeDashboard(VpnSessionStats(), server, 24) } } }
    @Test fun searching() { paparazzi.snapshot { VlessCardVpnTheme { HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTING,
        autoMode = true, progressMessage = "Авто: маршрут 3/12 · проверяем HTTPS"), server, 24) } } }
    @Test fun connected() { paparazzi.snapshot { VlessCardVpnTheme { HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTED,
        activeConfig = server, autoMode = true, profileLabel = "TLS-фрагментация", health = TunnelHealthReport(listOf(
            TunnelProbe("Cloudflare", 120, 204), TunnelProbe("YouTube · HTTPS", 210, 204), TunnelProbe("Telegram · веб", 170, 200, 200)), 1L)), server, 24) } } }
    @Test fun errorLargeText() { paparazzi.snapshot { VlessCardVpnTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
            HomeDashboard(VpnSessionStats(status = VpnStatus.ERROR, errorMessage = "Сервер не передаёт HTTPS. Авто попробует другой маршрут."), server, 24)
        }
    } } }
}
