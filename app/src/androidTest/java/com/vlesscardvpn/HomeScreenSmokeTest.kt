package com.vlesscardvpn

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.ui.HomeScreen
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeScreenSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = AppRepository(context)
    private val pilot = AutoPilotEngine(context, repo.getDatabase())
    @After fun release() { pilot.release(); repo.close() }

    private fun show(stats: VpnSessionStats = VpnSessionStats(), preparing: Boolean = false) {
        compose.setContent {
            VlessCardVpnTheme {
                HomeScreen(repo, pilot, stats, {}, {}, {}, {}, {}, {}, preparing, {})
            }
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun disconnectedScreenHasConnectAndTlsProfile() {
        show()
        compose.onNodeWithText("ГОТОВ К ПОДКЛЮЧЕНИЮ").assertIsDisplayed()
        capture("home-disconnected")
        compose.onNodeWithText("Авто").assertIsDisplayed()
        capture("home-profile")
    }
    @Test fun preparingScreenAllowsCancellation() {
        show(preparing = true)
        compose.onNodeWithText("Отменить").assertIsDisplayed()
        capture("home-preparing")
    }
    @Test fun errorScreenOffersCrashReports() {
        show(VpnSessionStats(status = VpnStatus.ERROR, errorMessage = "Тестовая ошибка: проверьте конфигурацию сервера."))
        compose.onNodeWithText("Отчёты об ошибках").performScrollTo().assertIsDisplayed()
        capture("home-error")
    }
}
