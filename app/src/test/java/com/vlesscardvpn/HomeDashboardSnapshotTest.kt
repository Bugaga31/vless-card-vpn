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
    private val server = VlessConfig(name = "Личный сервер", address = "vpn.example.org", port = 443, uuid = "test")
    @Test fun lightDisconnected() { paparazzi.snapshot { VlessCardVpnTheme(false) { HomeDashboard(VpnSessionStats(), server, 24) } } }
    @Test fun darkDisconnected() { paparazzi.snapshot { VlessCardVpnTheme(true) { HomeDashboard(VpnSessionStats(), server, 24) } } }
    @Test fun searching() { paparazzi.snapshot { VlessCardVpnTheme(false) { HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTING,
        autoMode = true, progressMessage = "Маршрут 3/36 · VPN + ByeDPI · проверяем HTTPS"), server, 24) } } }
    @Test fun connected() { paparazzi.snapshot { VlessCardVpnTheme(true) { HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTED,
        activeConfig = server, autoMode = true, profileLabel = "VPN + ByeDPI", durationSeconds = 342, health = TunnelHealthReport(listOf(
            TunnelProbe("Cloudflare", 120, 204), TunnelProbe("YouTube · HTTPS", 210, 204), TunnelProbe("Telegram · веб", 170, 200, 200)), 1L)), server, 24) } } }
    @Test fun errorLargeText() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            HomeDashboard(VpnSessionStats(status = VpnStatus.ERROR, errorMessage = "Сервер не передаёт HTTPS. Попробуйте Авто или добавьте подписку."), server, 24)
        }
    } } }
    @Test fun emptyState() { paparazzi.snapshot { VlessCardVpnTheme(false) { HomeDashboard(VpnSessionStats()) } } }
    @Test fun partialServiceFailure() { paparazzi.snapshot { VlessCardVpnTheme(false) { HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTED,
        activeConfig = server, profileLabel = "Параметры сервера", health = TunnelHealthReport(listOf(TunnelProbe("Cloudflare", 120, 204),
            TunnelProbe("YouTube · HTTPS", -1, -1), TunnelProbe("Telegram · веб", -1, -1, 200)), 1L)), server, 24) } } }
    @Test fun errorLargeTextBottom() { paparazzi.snapshot { VlessCardVpnTheme(false) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            HomeDashboard(VpnSessionStats(status = VpnStatus.ERROR, errorMessage = "Сервер не передаёт HTTPS. Попробуйте Авто или добавьте подписку."),
                server, 24, scrollState = androidx.compose.foundation.ScrollState(1500))
        }
    } } }
    @Test fun connectedBottom() { paparazzi.snapshot { VlessCardVpnTheme(true) {
        HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTED, profileLabel = "VPN + ByeDPI", durationSeconds = 342,
            health = TunnelHealthReport(listOf(TunnelProbe("Cloudflare", 120, 204), TunnelProbe("YouTube · HTTPS", 210, 204),
                TunnelProbe("Telegram · веб", 170, 200, 200)), 1)), server, 24, scrollState = androidx.compose.foundation.ScrollState(1500))
    } } }

}
