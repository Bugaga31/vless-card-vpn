package com.vlesscardvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.ServerCard
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.util.UniversalConfigParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    repo: AppRepository,
    onConnect: (VlessConfig) -> Unit,
    onNavigateToFree: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var isPingingAll by remember { mutableStateOf(false) }

    val filteredConfigs = remember(configs, searchQuery) {
        configs.filter {
            searchQuery.isBlank() ||
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.address.contains(searchQuery, ignoreCase = true) ||
            it.country.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Core Nodes (${configs.size})", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isPingingAll = true
                            scope.launch {
                                repo.testAllConfigs()
                                isPingingAll = false
                            }
                        }
                    ) {
                        if (isPingingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NeonCyan, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = "Ping All", tint = NeonCyan)
                        }
                    }
                    IconButton(onClick = onNavigateToFree) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Free Repositories", tint = NeonGreen)
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Import", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by node name, IP or country...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Server items list
            if (filteredConfigs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No proxy nodes found. Tap + or Free Nodes to add.", fontSize = 13.sp, color = TextTertiary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredConfigs, key = { it.id }) { config ->
                        ServerCard(
                            config = config,
                            onConnect = { onConnect(config) },
                            onPing = {
                                scope.launch {
                                    val breakdown = com.vlesscardvpn.domain.PingTester.testDetailedLatency(config)
                                    val ping = if (breakdown.success) {
                                        if (breakdown.tlsMs > 0) breakdown.tlsMs else breakdown.tcpMs
                                    } else -1
                                    repo.updateConfig(config.copy(
                                        pingMs = ping,
                                        tcpLatencyMs = breakdown.tcpMs,
                                        tlsLatencyMs = breakdown.tlsMs,
                                        healthState = if (ping > 0) "HEALTHY" else "DEAD"
                                    ))
                                }
                            },
                            onDelete = {
                                scope.launch { repo.deleteConfig(config.id) }
                            },
                            onToggleFavorite = {
                                scope.launch { repo.toggleFavorite(config.id) }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import VLESS / VMess / Trojan", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("Paste raw URI links (vless://, vmess://, trojan://, ss://) or subscription text:", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        placeholder = { Text("vless://...", fontSize = 11.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = UniversalConfigParser.parseAny(importText)
                        if (parsed.isNotEmpty()) {
                            scope.launch {
                                repo.addConfigs(parsed)
                                Toast.makeText(context, "Imported ${parsed.size} nodes", Toast.LENGTH_SHORT).show()
                            }
                            showImportDialog = false
                            importText = ""
                        } else {
                            Toast.makeText(context, "No valid configs detected", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF090A0F))
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}
