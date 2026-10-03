package com.vlesscardvpn

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.ui.HomeDashboard
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
        compose.onNodeWithText("Не подключено").assertIsDisplayed()
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
        compose.onNodeWithText("Диагностика подключения").performScrollTo().performClick()
        compose.onNodeWithText("Журнал и отправка отчёта").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скрыть диагностику").performScrollTo().performClick()
        compose.onNodeWithText("Журнал и отправка отчёта").assertDoesNotExist()
        compose.onNodeWithText("Отчёты об ошибках").performScrollTo().assertIsDisplayed()
        capture("home-error")
    }
    @Test fun manualAndAutoActionsStaySeparate() {
        val manual = java.util.concurrent.atomic.AtomicInteger()
        val automatic = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent { VlessCardVpnTheme {
            HomeDashboard(VpnSessionStats(), VlessConfig(name = "Fixture", address = "127.0.0.1", port = 443, uuid = "fixture"),
                onConnect = { manual.incrementAndGet() }, onAuto = { automatic.incrementAndGet() })
        } }
        compose.onNodeWithText("Подключить").performClick()
        org.junit.Assert.assertEquals(1, manual.get()); org.junit.Assert.assertEquals(0, automatic.get())
        compose.onNodeWithText("Авто").performClick()
        org.junit.Assert.assertEquals(1, manual.get()); org.junit.Assert.assertEquals(1, automatic.get())
    }
    @Test fun failedServiceShowsActualTlsStage() {
        compose.setContent { VlessCardVpnTheme {
            HomeDashboard(VpnSessionStats(status = VpnStatus.CONNECTED, health = TunnelHealthReport(listOf(
                TunnelProbe("Cloudflare", 50, 204), TunnelProbe("YouTube · HTTPS", 4000, failure = DiagnosticFailure.TIMEOUT,
                    stage = DiagnosticFailure.TLS)), 1)))
        } }
        compose.onNodeWithText("Таймаут TLS").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Нет результата").performScrollTo().assertIsDisplayed()
    }
    @Test fun enlargedTextKeepsBothActionsReachable() {
        compose.setContent { VlessCardVpnTheme {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 1.5f)) {
                HomeDashboard(VpnSessionStats(), VlessConfig(name = "Fixture", address = "127.0.0.1", port = 443, uuid = "fixture"))
            }
        } }
        compose.onNodeWithText("Подключить").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Авто").performScrollTo().assertIsDisplayed()
    }

}
