package com.vlesscardvpn
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.vlesscardvpn.ui.HomeDashboard
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.worker.*
import org.junit.Rule
import org.junit.Test
class SmallHomeSnapshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 640, screenHeight = 1280, density = com.android.resources.Density.XHIGH),
        theme = "android:style/Theme.Material.NoActionBar")
    @Test fun smallIdle() { paparazzi.snapshot { VlessCardVpnTheme(false) { HomeDashboard(VpnSessionStats()) } } }
}
