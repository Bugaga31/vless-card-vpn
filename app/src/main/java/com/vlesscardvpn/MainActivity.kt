package com.vlesscardvpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.*
import com.vlesscardvpn.ui.CrashReportsScreen
import com.vlesscardvpn.ui.components.InstrumentSplashScreen
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.worker.SubscriptionUpdateWorker
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shouldAutoConnect = intent.getBooleanExtra("EXTRA_AUTO_CONNECT", false)

        setContent {
            VlessCardVpnTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VlessCardVpnApp(
                        autoConnectOnStart = shouldAutoConnect,
                        onPanicExit = {
                            VlessVpnService.stopVpn(this@MainActivity)
                            finishAffinity()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VlessCardVpnApp(
    autoConnectOnStart: Boolean = false,
    onPanicExit: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()

    val repo = remember { AppRepository(context.applicationContext) }
    val autoPilotEngine = remember { AutoPilotEngine(context.applicationContext, repo.getDatabase()) }

    DisposableEffect(repo, autoPilotEngine) {
        onDispose {
            autoPilotEngine.release()
            repo.close()
        }
    }

    val vpnStats by VlessVpnService.vpnStats.collectAsState()
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val settings by repo.settingsFlow.collectAsState()

    var showSplash by remember { mutableStateOf(true) }
    var pendingConfig by remember { mutableStateOf<VlessConfig?>(null) }

    LaunchedEffect(Unit) {
        SubscriptionUpdateWorker.schedulePeriodic(context.applicationContext)
        delay(800)
        showSplash = false
        if (settings.autoSelect) autoPilotEngine.startAutoPilot(settings.healthCheckInterval)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConfig?.let { cfg ->
                scope.launch {
                    repo.setActive(cfg.id)
                    VlessVpnService.startVpn(context, cfg)
                }
            }
        } else {
            Toast.makeText(context, "Разрешение VPN необходимо для подключения", Toast.LENGTH_SHORT).show()
        }
        pendingConfig = null
    }

    val handleConnectToggle: (VlessConfig?) -> Unit = { targetConfig ->
        scope.launch {
            if (vpnStats.status == VpnStatus.CONNECTED || vpnStats.status == VpnStatus.CONNECTING) {
                VlessVpnService.stopVpn(context)
                repo.setActive("")
                return@launch
            }

            // 1-Click Full Automation Connect:
            // 1. If user passed a specific config, use it.
            // 2. Otherwise find lowest-ping verified config among existing ones.
            // 3. If none exist or none verified, automatically fetch live subscription pool, ping in parallel, and connect to the fastest node!
            var cfgToConnect = targetConfig ?: configs.filter { it.pingMs in 1..1500 }.minByOrNull { it.pingMs }
                ?: configs.firstOrNull { it.isActive }
                ?: configs.firstOrNull()

            if (cfgToConnect == null || (targetConfig == null && cfgToConnect.pingMs <= 0)) {
                Toast.makeText(context, "⚡ 1-Click: Сканирование пула подписок и выбор быстрейшего узла…", Toast.LENGTH_SHORT).show()
                val working = PublicConfigFetcher.fetchAndFilterWorkingConfigs(maxWorkingCount = 20)
                if (working.isNotEmpty()) {
                    working.forEach { repo.addConfig(it) }
                    cfgToConnect = working.minByOrNull { if (it.pingMs > 0) it.pingMs else 9999 } ?: working.first()
                }
            }

            if (cfgToConnect != null) {
                // Кнопка «Подключить» = полный автомат: автопилот всегда идёт в связке
                // с туннелем — фон-скан подписок, перепинг и failover на быстрейший узел.
                runCatching { autoPilotEngine.setConsent(true) }
                runCatching { autoPilotEngine.startAutoPilot(settings.healthCheckInterval) }
                scope.launch { runCatching { autoPilotEngine.triggerManualScan() } }
                try {
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent != null) {
                        pendingConfig = cfgToConnect
                        vpnPermissionLauncher.launch(prepareIntent)
                    } else {
                        repo.setActive(cfgToConnect.id)
                        VlessVpnService.startVpn(context, cfgToConnect)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "VPN permission prepare failed", e)
                    Toast.makeText(context, "Ошибка запроса разрешения VPN: ${e.localizedMessage ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Не удалось найти рабочий узел связи. Проверьте интернет-соединение.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Запрос разрешения VPN сразу при первом запуске (после установки/открытия)
    var vpnPermissionChecked by remember { mutableStateOf(false) }
    var autoConnectAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(showSplash) {
        if (!showSplash && !vpnPermissionChecked) {
            vpnPermissionChecked = true
            try {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Early VPN permission check failed", e)
            }
        }
    }

    // Полный автомат: однократное автоподключение при старте приложения
    LaunchedEffect(showSplash, settings.autoSelect, autoConnectOnStart) {
        if (!showSplash && (settings.autoSelect || autoConnectOnStart) && !autoConnectAttempted) {
            autoConnectAttempted = true
            delay(1500) // даём подпискам и БД прогрузиться
            if (vpnStats.status == VpnStatus.DISCONNECTED) {
                handleConnectToggle(null)
            }
        }
    }

    AnimatedContent(
        targetState = showSplash,
        transitionSpec = { fadeIn(animationSpec = tween(280)) togetherWith fadeOut(animationSpec = tween(280)) },
        label = "AppScreenTransition"
    ) { isSplash ->
        if (isSplash) {
            InstrumentSplashScreen()
        } else {
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        repo = repo,
                        autoPilotEngine = autoPilotEngine,
                        vpnStats = vpnStats,
                        onToggleConnect = handleConnectToggle,
                        onNavigateToServers = { navController.navigate("servers") },
                        onNavigateToAutopilot = { navController.navigate("autopilot") },
                        onNavigateToDiagnostic = { navController.navigate("diagnostic") },
                        onNavigateToSettings = { navController.navigate("settings") },
                        onPanicTrigger = onPanicExit
                    )
                }
                composable("servers") {
                    ServersScreen(repo, handleConnectToggle, { navController.navigate("free_configs") }) { navController.popBackStack() }
                }
                composable("autopilot") { AutopilotScreen(repo, autoPilotEngine) { navController.popBackStack() } }
                composable("diagnostic") { DiagnosticScreen(repo) { navController.popBackStack() } }
                composable("stealth") { StealthProfileScreen(repo) { navController.popBackStack() } }
                composable("settings") { SettingsScreen(repo, onNavigateToCrashReports = { navController.navigate("crash_reports") }) { navController.popBackStack() } }
                composable("free_configs") { FreeConfigsScreen(repo) { navController.popBackStack() } }
                composable("crash_reports") { CrashReportsScreen(repo) { navController.popBackStack() } }
            }
        }
    }
}
