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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.ServerCard
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.util.UniversalConfigParser
import kotlinx.coroutines.launch

enum class ServerSortMode(val label: String) {
    LATENCY("По задержке"),
    FAVORITES("Избранные"),
    PROTOCOL("Протокол"),
    NAME("По имени")
}

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
    var sortMode by remember { mutableStateOf(ServerSortMode.LATENCY) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var isPingingAll by remember { mutableStateOf(false) }

    val activeConfig = configs.firstOrNull { it.isActive }

    val sortedAndFilteredConfigs = remember(configs, searchQuery, sortMode) {
        val filtered = configs.filter {
            searchQuery.isBlank() ||
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.address.contains(searchQuery, ignoreCase = true) ||
            it.sni.contains(searchQuery, ignoreCase = true)
        }
        when (sortMode) {
            ServerSortMode.LATENCY -> filtered.sortedWith(compareBy({ it.pingMs <= 0 }, { it.pingMs }))
            ServerSortMode.FAVORITES -> filtered.sortedByDescending { it.isFavorite }
            ServerSortMode.PROTOCOL -> filtered.sortedBy { it.protocolType }
            ServerSortMode.NAME -> filtered.sortedBy { it.name }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Узлы связи",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${configs.size} доступно",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    // Ping all button
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
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = SignalOrange, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = "Проверить все", tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }

                    // Free community nodes
                    IconButton(onClick = onNavigateToFree) {
                        Icon(Icons.Default.Public, contentDescription = "Публичные репозитории", tint = MaterialTheme.colorScheme.onBackground)
                    }

                    // Add server
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить узел", tint = SignalOrange)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space8)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск по имени, адресу или SNI...", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SignalOrange,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium)
            )

            Spacer(modifier = Modifier.height(InstrumentDimens.space8))

            // Sort Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ServerSortMode.values().forEach { mode ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { sortMode = mode },
                        label = { Text(mode.label, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalOrangeContainer,
                            selectedLabelColor = SignalOrangeContent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space8))

            // Server List
            if (sortedAndFilteredConfigs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isBlank()) "Список серверов пуст" else "Узлы не найдены",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(InstrumentDimens.space8))
                        Button(
                            onClick = { showImportDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
                        ) {
                            Text("Добавить сервер")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space4)
                ) {
                    items(sortedAndFilteredConfigs, key = { it.id }) { config ->
                        val isSelected = activeConfig?.id == config.id

                        ServerCard(
                            config = config,
                            isConnected = config.isActive,
                            isSelected = isSelected,
                            onSelect = {
                                scope.launch {
                                    repo.setActive(config.id)
                                    Toast.makeText(context, "Выбран: ${config.name}", Toast.LENGTH_SHORT).show()
                                }
                            },
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

    // Import Dialog
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importError = null
            },
            title = {
                Text(
                    text = "Добавить узел связи",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)) {
                    Text(
                        text = "Поддерживаемые форматы: VLESS Reality (vless://), VMess (vmess://), Trojan (trojan://), Shadowsocks (ss://) или Base64 подписка.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        placeholder = { Text("Вставьте ссылку или текст...", fontSize = 12.sp, fontFamily = FontFamily.Monospace) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SignalOrange,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    if (importError != null) {
                        Text(
                            text = importError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = SemanticRed
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = UniversalConfigParser.parseAny(importText)
                        if (parsed.isNotEmpty()) {
                            scope.launch {
                                repo.addConfigs(parsed)
                                Toast.makeText(context, "Добавлено серверов: ${parsed.size}", Toast.LENGTH_SHORT).show()
                            }
                            showImportDialog = false
                            importText = ""
                            importError = null
                        } else {
                            importError = "Не удалось распознать конфигурацию. Проверьте формат ссылки (vless://, vmess://, trojan://)."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importError = null
                }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
