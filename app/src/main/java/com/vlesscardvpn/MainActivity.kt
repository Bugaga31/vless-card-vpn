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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import com.vlesscardvpn.core.CrashReportManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shouldAutoConnect = intent?.getBooleanExtra("EXTRA_AUTO_CONNECT", false) ?: false

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
    val uiErrors = remember(context) {
        CoroutineExceptionHandler { _, error ->
            CrashReportManager.recordException(context.applicationContext, Thread.currentThread(), error, "UI_TASK")
            Toast.makeText(context, "Ошибка операции. Подробности в отчётах об ошибках.", Toast.LENGTH_LONG).show()
        }
    }
    val scope = rememberCoroutineScope { uiErrors }
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
    var preparingConnection by remember { mutableStateOf(false) }
    var preparationJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        SubscriptionUpdateWorker.schedulePeriodic(context.applicationContext)
        delay(800)
        showSplash = false
        // Restore monitoring only for a tunnel that was already running. Opening the UI
        // must not start a tunnel or scan all nodes because a selection setting was saved.
        if (settings.autoSelect && VlessVpnService.vpnStats.value.status == VpnStatus.CONNECTED) {
            autoPilotEngine.startAutoPilot(settings.healthCheckInterval)
        }
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

    val handleConnectToggle: (VlessConfig?) -> Unit = toggle@{ targetConfig ->
        if (preparingConnection) {
            preparationJob?.cancel()
            return@toggle
        }
        if (pendingConfig != null) return@toggle // System VPN permission dialog is already open.
        preparationJob = scope.launch {
            preparingConnection = true
            try {
                if (VlessVpnService.vpnStats.value.status in listOf(VpnStatus.CONNECTED, VpnStatus.CONNECTING)) {
                    VlessVpnService.stopVpn(context)
                    repo.setActive("")
                    return@launch
                }
                var cfgToConnect = targetConfig
                    ?: configs.filter { it.pingMs in 1..1500 }.minByOrNull { it.pingMs }
                    ?: configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
                if (cfgToConnect == null) {
                    val working = PublicConfigFetcher.fetchAndFilterWorkingConfigs(maxWorkingCount = 20)
                    repo.addConfigs(working)
                    cfgToConnect = working.firstOrNull()
                }
                val config = cfgToConnect
                if (config == null) {
                    Toast.makeText(context, "Нет доступных узлов. Добавьте подписку или сервер.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                // A connect click does not override the user's monitoring consent.
                if (settings.autoSelect && settings.autopilotConsentGiven) {
                    autoPilotEngine.startAutoPilot(settings.healthCheckInterval)
                }
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    pendingConfig = config
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    repo.setActive(config.id)
                    VlessVpnService.startVpn(context, config)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashReportManager.recordException(context.applicationContext, Thread.currentThread(), error, "CONNECT_UI")
                Toast.makeText(context, "Не удалось подготовить подключение. Откройте отчёты об ошибках.", Toast.LENGTH_LONG).show()
            } finally {
                preparingConnection = false
            }
        }
    }

    var autoConnectAttempted by remember { mutableStateOf(false) }

    // Autoselection is not consent to connect on every launcher open.
    // Only an explicit Quick Settings action requests connection on entry.
    LaunchedEffect(showSplash, autoConnectOnStart) {
        if (!showSplash && autoConnectOnStart && !autoConnectAttempted) {
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
                        onPanicTrigger = onPanicExit,
                        preparingConnection = preparingConnection,
                        onNavigateToCrashReports = { navController.navigate("crash_reports") }
                    )
                }
                composable("servers") {
                    ServersScreen(repo, handleConnectToggle, { navController.navigate("free_configs") }) { navController.popBackStack() }
                }
                composable("autopilot") { AutopilotScreen(repo, autoPilotEngine) { navController.popBackStack() } }
                composable("diagnostic") { DiagnosticScreen(repo) { navController.popBackStack() } }
                composable("stealth") { StealthProfileScreen(repo) { navController.popBackStack() } }
                composable("settings") { SettingsScreen(repo, autoPilotEngine, onNavigateToCrashReports = { navController.navigate("crash_reports") }) { navController.popBackStack() } }
                composable("free_configs") { FreeConfigsScreen(repo) { navController.popBackStack() } }
                composable("crash_reports") { CrashReportsScreen(repo) { navController.popBackStack() } }
            }
        }
    }
}
