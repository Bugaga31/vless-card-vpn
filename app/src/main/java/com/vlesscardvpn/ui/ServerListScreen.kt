package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.ServerCard
import com.vlesscardvpn.util.VlessUriParser
import com.vlesscardvpn.worker.VlessVpnService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    repo: InMemoryConfigRepo = remember { InMemoryConfigRepo() },
    onNavigateToFree: () -> Unit = {}
) {
    val context = LocalContext.current
    val configs by repo.configs.collectAsState(initial = emptyList())
    var showImportDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VLESS Card VPN") },
                actions = {
                    IconButton(onClick = onNavigateToFree) {
                        Icon(Icons.Default.Add, contentDescription = "Free configs")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Default.Add, "Import VLESS")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(configs, key = { it.id }) { config ->
                    ServerCard(
                        config = config,
                        onConnect = { cfg ->
                            if (cfg.isActive) {
                                VlessVpnService.stopVpn(context)
                                repo.setActive("")
                            } else {
                                VlessVpnService.startVpn(context, cfg)
                                repo.setActive(cfg.id)
                            }
                        },
                        onPing = { cfg ->
                            scope.launch {
                                val ping = PingTester.pingConfig(cfg)
                                repo.updatePing(cfg.id, ping)
                            }
                        },
                        onDelete = { cfg ->
                            repo.deleteConfig(cfg.id)
                            if (cfg.isActive) VlessVpnService.stopVpn(context)
                        }
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import VLESS URI") },
            text = {
                OutlinedTextField(
                    value = importUri,
                    onValueChange = { importUri = it },
                    label = { Text("vless://...") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val parsed = VlessUriParser.parse(importUri)
                    if (parsed != null) {
                        repo.addConfig(parsed)
                        importUri = ""
                        showImportDialog = false
                    }
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}