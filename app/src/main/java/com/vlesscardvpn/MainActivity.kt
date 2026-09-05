package com.vlesscardvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.ui.FreeConfigsScreen
import com.vlesscardvpn.ui.ServerListScreen
import com.vlesscardvpn.ui.SettingsScreen
import com.vlesscardvpn.ui.theme.VlessCardVpnTheme

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
    val navController = rememberNavController()
    val repo = remember { InMemoryConfigRepo() }

    NavHost(
        navController = navController,
        startDestination = "server_list"
    ) {
        composable("server_list") {
            ServerListScreen(
                repo = repo,
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
                onBack = { navController.popBackStack() }
            )
        }
    }
}
