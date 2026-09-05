package com.vlesscardvpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.ServerCard
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.util.UniversalConfigParser
import com.vlesscardvpn.worker.VlessVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    repo: InMemoryConfigRepo,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFree: () -> Unit = {}
) {
    val context = LocalContext.current
    val rawConfigs by repo.configs.collectAsState(initial = emptyList())
    val settings by repo.settings.collectAsState(initial = com.vlesscardvpn.domain.AppSettings())
    val isFetching by repo.isFetching.collectAsState(initial = false)
    val fetchStatus by repo.fetchStatus.collectAsState(initial = "")

    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Sorted configs if setting enabled
    val configs = remember(rawConfigs, settings.autoSelectBestPing) {
        if (settings.autoSelectBestPing) {
            rawConfigs.sortedWith(
                compareBy<VlessConfig> { if (it.pingMs > 0) 0 else 1 }
                    .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
            )
        } else {
            rawConfigs
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("VLESS Reality", fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = NeonCyan.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${configs.size}",
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    // Ping all servers button
                    IconButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                configs.forEach { cfg ->
                                    val ping = PingTester.pingConfig(cfg)
                                    repo.updatePing(cfg.id, ping)
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = "Test All Pings", tint = NeonGreen)
                    }

                    // Settings
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImportDialog = true },
                containerColor = NeonCyan,
                contentColor = Color(0xFF0F1117)
            ) {
                Icon(Icons.Default.Add, "Import Config / Subscription")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Big Action Banner: Parse & Fetch All Public Working Configs
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🌐 Public Working Nodes",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Scan open repos, test latency, extract alive VLESS nodes.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (!isFetching) {
                                    scope.launch {
                                        repo.setFetching(true, "Scanning public sources...")
                                        try {
                                            val working = PublicConfigFetcher.fetchAndFilterWorkingConfigs(
                                                sources = settings.autoFetchSources,
                                                onProgress = { scanned, workingCount, msg ->
                                                    repo.setFetching(true, "$msg (Found $workingCount alive / $scanned scanned)")
                                                }
                                            )
                                            repo.addConfigs(working)
                                            snackbarHostState.showSnackbar("Added ${working.size} active working servers!")
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("Fetch failed: ${e.localizedMessage}")
                                        } finally {
                                            repo.setFetching(false, "")
                                        }
                                    }
                                }
                            },
                            enabled = !isFetching,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonPurple,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isFetching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Text("Auto-Parse", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    AnimatedVisibility(visible = isFetching) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = NeonCyan,
                                trackColor = Color(0xFF2E3349)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = fetchStatus, fontSize = 11.sp, color = NeonCyan)
                        }
                    }
                }
            }

            // Server List
            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No servers added yet", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Tap 'Auto-Parse' or the + button to import configs",
                            color = TextSecondary.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
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
                                scope.launch(Dispatchers.IO) {
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
    }

    if (showImportDialog) {
        AlertDialog(
            containerColor = DarkSurface,
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Config or Subscription", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Paste raw vless://, vmess://, trojan://, ss:// link or base64 subscription text/URL:",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("vless://... or https://subscription-link") },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = Color(0xFF2E3349),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedList = UniversalConfigParser.parseAny(importText)
                        if (parsedList.isNotEmpty()) {
                            repo.addConfigs(parsedList)
                            scope.launch {
                                snackbarHostState.showSnackbar("Imported ${parsedList.size} configs!")
                            }
                            importText = ""
                            showImportDialog = false
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar("Could not parse config. Check link format.")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF0F1117))
                ) {
                    Text("Import", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}
