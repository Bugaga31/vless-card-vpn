package com.vlesscardvpn
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.ui.NetworkDiagnosticsCard
import com.vlesscardvpn.ui.NetworkReportDialog
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import org.junit.Rule
import org.junit.Test
class NetworkDiagnosticsSnapshotTest {
 @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, theme = "android:style/Theme.Material.NoActionBar")
 @Test fun light() { paparazzi.snapshot { VlessCardVpnTheme(false) { Surface(Modifier.padding(20.dp)) { NetworkDiagnosticsCard() } } } }
 @Test fun darkChecking() { paparazzi.snapshot { VlessCardVpnTheme(true) { Surface(Modifier.padding(20.dp)) { NetworkDiagnosticsCard(true) } } } }
 @Test fun largeTextNoVpn() { paparazzi.snapshot { VlessCardVpnTheme(false) { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
 Surface(Modifier.padding(20.dp)) { NetworkDiagnosticsCard(note = "Сначала подключите VPN. Прямая проверка не подменяет проверку туннеля.") }
 } } } }
 @Test fun reportLight() { paparazzi.snapshot { VlessCardVpnTheme(false) { NetworkReportDialog("49 | HTTPS_CHECK | YOUTUBE | NONE | NONE | TLS | -1 | -1\n50 | HTTPS_CHECK | TELEGRAM | NONE | NONE | NONE | 200 | 234") } } }
 @Test fun reportDark() { paparazzi.snapshot { VlessCardVpnTheme(true) { NetworkReportDialog("49 | ROUTE_START | NONE | BYEDPI | TCP_ONLY | NONE | -1 | -1\n50 | HTTPS_CHECK | YOUTUBE | NONE | NONE | NONE | 204 | 234") } } }
 @Test fun reportLarge() { paparazzi.snapshot { VlessCardVpnTheme(false) { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
 NetworkReportDialog("49 | HTTPS_CHECK | YOUTUBE | NONE | NONE | TIMEOUT | -1 | -1")
 } } } }

}
