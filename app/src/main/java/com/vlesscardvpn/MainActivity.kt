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
import com.vlesscardvpn.ui.components.InstrumentSplashScreen
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
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
            autoPilotEngine.close()
            repo.close()
        }
    }

    val vpnStats by VlessVpnService.vpnStats.collectAsState()
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val settings by repo.settingsFlow.collectAsState()

    var showSplash by remember { mutableStateOf(true) }
    var pendingConfig by remember { mutableStateOf<VlessConfig?>(null) }

    LaunchedEffect(Unit) {
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
        val cfgToConnect = targetConfig ?: configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
        if (vpnStats.status == VpnStatus.CONNECTED || vpnStats.status == VpnStatus.CONNECTING) {
            VlessVpnService.stopVpn(context)
            scope.launch { repo.setActive("") }
        } else if (cfgToConnect != null) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                pendingConfig = cfgToConnect
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                scope.launch {
                    repo.setActive(cfgToConnect.id)
                    VlessVpnService.startVpn(context, cfgToConnect)
                }
            }
        } else {
            Toast.makeText(context, "Сначала импортируйте или выберите сервер", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(autoConnectOnStart, showSplash) {
        if (autoConnectOnStart && !showSplash && vpnStats.status == VpnStatus.DISCONNECTED) {
            handleConnectToggle(null)
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
                        onPanicTrigger = onPanicExit
                    )
                }
                composable("servers") {
                    ServersScreen(repo, handleConnectToggle, { navController.navigate("free_configs") }) { navController.popBackStack() }
                }
                composable("autopilot") { AutopilotScreen(repo, autoPilotEngine) { navController.popBackStack() } }
                composable("diagnostic") { DiagnosticScreen(repo) { navController.popBackStack() } }
                composable("stealth") { StealthProfileScreen(repo) { navController.popBackStack() } }
                composable("settings") { SettingsScreen(repo) { navController.popBackStack() } }
                composable("free_configs") { FreeConfigsScreen(repo) { navController.popBackStack() } }
            }
        }
    }
}
