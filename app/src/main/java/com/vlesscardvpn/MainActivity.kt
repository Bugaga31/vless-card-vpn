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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.FreeConfigsScreen
import com.vlesscardvpn.ui.ServerListScreen
import com.vlesscardvpn.ui.SettingsScreen
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VlessCardVpnTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VlessCardVpnApp()
                }
            }
        }
    }
}

@Composable
fun VlessCardVpnApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val repo = remember { InMemoryConfigRepo() }
    val vpnStats by VlessVpnService.vpnStats.collectAsState()
    val configs by repo.configs.collectAsState(initial = emptyList())

    var pendingConfig by remember { mutableStateOf<VlessConfig?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConfig?.let { cfg ->
                repo.setActive(cfg.id)
                VlessVpnService.startVpn(context, cfg)
            }
        } else {
            Toast.makeText(context, "VPN Permission is required to connect", Toast.LENGTH_SHORT).show()
        }
        pendingConfig = null
    }

    val handleConnectToggle: (VlessConfig?) -> Unit = { targetConfig ->
        val cfgToConnect = targetConfig ?: configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
        if (vpnStats.status == VpnStatus.CONNECTED || vpnStats.status == VpnStatus.CONNECTING) {
            VlessVpnService.stopVpn(context)
            repo.setActive("")
        } else if (cfgToConnect != null) {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                pendingConfig = cfgToConnect
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                repo.setActive(cfgToConnect.id)
                VlessVpnService.startVpn(context, cfgToConnect)
            }
        } else {
            Toast.makeText(context, "Please add or select a server first", Toast.LENGTH_SHORT).show()
        }
    }

    NavHost(
        navController = navController,
        startDestination = "server_list"
    ) {
        composable("server_list") {
            ServerListScreen(
                repo = repo,
                vpnStats = vpnStats,
                onToggleConnect = handleConnectToggle,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToFree = { navController.navigate("free_configs") }
            )
        }
        composable("settings") {
            SettingsScreen(
                repo = repo,
                onBack = { navController.popBackStack() }
            )
        }
        composable("free_configs") {
            FreeConfigsScreen(
                repo = repo,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
