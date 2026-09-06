package com.vlesscardvpn.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.StealthSettings
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.CyberGlobeMapView
import com.vlesscardvpn.ui.components.CyberParticleBackground
import com.vlesscardvpn.ui.components.ServerCard
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.util.UniversalConfigParser
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    repo: InMemoryConfigRepo,
    vpnStats: VpnSessionStats,
    onToggleConnect: (VlessConfig?) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToFree: () -> Unit = {},
    onNavigateToDiagnostic: () -> Unit = {},
    onNavigateToStealth: () -> Unit = {},
    onPanicTrigger: () -> Unit = {}
) {
    val rawConfigs by repo.configs.collectAsState(initial = emptyList())
    val settings by repo.settings.collectAsState(initial = com.vlesscardvpn.domain.AppSettings())
    val stealthSettings by repo.stealthSettings.collectAsState(initial = StealthSettings())
    val isFetching by repo.isFetching.collectAsState(initial = false)
    val fetchStatus by repo.fetchStatus.collectAsState(initial = "")

    var selectedProtocolFilter by remember { mutableStateOf("ALL") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Double tap panic button detector
    var lastTapTime by remember { mutableStateOf(0L) }

    // Filter & Sort
    val workingFiltered = if (settings.showOnlyWorkingNodes) rawConfigs.filter { it.pingMs > 0 } else rawConfigs
    val protocolFiltered = if (selectedProtocolFilter == "ALL") workingFiltered else workingFiltered.filter { it.protocolType.equals(selectedProtocolFilter, ignoreCase = true) }

    val configs = remember(protocolFiltered, settings.autoSelectBestPing) {
        if (settings.autoSelectBestPing) {
            protocolFiltered.sortedWith(
                compareBy<VlessConfig> { if (it.pingMs > 0) 0 else 1 }
                    .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE }
            )
        } else {
            protocolFiltered
        }
    }

    val activeConfig = configs.firstOrNull { it.isActive } ?: vpnStats.activeConfig

    Box(modifier = Modifier.fillMaxSize()) {
        CyberParticleBackground(particleCount = 25)

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 400) {
                                    onPanicTrigger()
                                }
                                lastTapTime = now
                            }
                        ) {
                            Text(
                                text = "VLESS COCKPIT",
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = NeonCyan.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(NeonCyan)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "${configs.size}",
                                        color = NeonCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Diagnostic Button
                        IconButton(onClick = onNavigateToDiagnostic) {
                            Icon(Icons.Default.Speed, contentDescription = "Diagnostic", tint = NeonCyan)
                        }

                        // Stealth & Fractal Profile Button
                        IconButton(onClick = onNavigateToStealth) {
                            Icon(Icons.Default.Fingerprint, contentDescription = "Stealth", tint = NeonPurple)
                        }

                        // Free Sources Screen Shortcut
                        IconButton(onClick = onNavigateToFree) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Public Sources", tint = NeonGreen)
                        }

                        // Settings
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface.copy(alpha = 0.85f))
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showImportDialog = true },
                    containerColor = NeonCyan,
                    contentColor = Color(0xFF090A0F),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, "Import Config")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 1. Stealth Cockpit Dashboard with Live Hologram Map
                VpnCockpitDashboard(
                    stats = vpnStats,
                    activeConfig = activeConfig,
                    stealthSettings = stealthSettings,
                    onConnectClick = { onToggleConnect(activeConfig) },
                    onNavigateToStealth = onNavigateToStealth
                )

                // 2. Protocol Filter Bar & Auto-parse Action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        val filters = listOf("ALL", "VLESS", "VMESS", "TROJAN", "SS")
                        items(filters) { filter ->
                            val isSelected = selectedProtocolFilter == filter
                            Surface(
                                modifier = Modifier.clickable { selectedProtocolFilter = filter },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) NeonCyan.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) NeonCyan else DarkBorder
                                )
                            ) {
                                Text(
                                    text = filter,
                                    color = if (isSelected) NeonCyan else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Auto-parse button
                    Button(
                        onClick = {
                            if (!isFetching) {
                                scope.launch {
                                    repo.setFetching(true, "Scanning open nodes...")
                                    try {
                                        val working = PublicConfigFetcher.fetchAndFilterWorkingConfigs(
                                            sources = settings.autoFetchSources,
                                            onProgress = { scanned, alive, msg ->
                                                repo.setFetching(true, "$msg ($alive alive / $scanned)")
                                            }
                                        )
                                        val limited = working.take(settings.maxFreeNodesToAdd)
                                        repo.addConfigs(limited)
                                        snackbarHostState.showSnackbar("Added ${limited.size} active nodes!")
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Scan error: ${e.localizedMessage}")
                                    } finally {
                                        repo.setFetching(false, "")
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonPurple,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Auto-Parse", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                AnimatedVisibility(visible = isFetching) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonCyan, trackColor = DarkBorder)
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(text = fetchStatus, fontSize = 11.sp, color = NeonCyan)
                    }
                }

                // 3. Server List
                if (configs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No servers available", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Tap 'Auto-Parse' or press '+' to add servers",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        items(configs, key = { it.id }) { config ->
                            ServerCard(
                                config = config,
                                onConnect = { onToggleConnect(it) },
                                onPing = { cfg ->
                                    scope.launch(Dispatchers.IO) {
                                        val ping = PingTester.pingConfig(cfg)
                                        repo.updatePing(cfg.id, ping)
                                    }
                                },
                                onDelete = { cfg ->
                                    repo.deleteConfig(cfg.id)
                                    if (cfg.isActive) onToggleConnect(null)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            containerColor = DarkSurface,
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import Config / Subscription", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Paste vless://, vmess://, trojan://, ss:// or Base64 link:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text("vless://... or base64") },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
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
                            repo.addConfigs(parsed)
                            scope.launch {
                                snackbarHostState.showSnackbar("Imported ${parsed.size} configs!")
                            }
                        }
                        showImportDialog = false
                        importText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF090A0F))
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

@Composable
private fun VpnCockpitDashboard(
    stats: VpnSessionStats,
    activeConfig: VlessConfig?,
    stealthSettings: StealthSettings,
    onConnectClick: () -> Unit,
    onNavigateToStealth: () -> Unit = {}
) {
    val isConnected = stats.status == VpnStatus.CONNECTED
    val isConnecting = stats.status == VpnStatus.CONNECTING

    val infiniteTransition = rememberInfiniteTransition(label = "CorePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected || isConnecting) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CoreScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, if (isConnected) NeonGreen.copy(alpha = 0.6f) else NeonCyan.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status row + Stealth Profile badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = when (stats.status) {
                        VpnStatus.CONNECTED -> NeonGreen.copy(alpha = 0.15f)
                        VpnStatus.CONNECTING -> NeonAmber.copy(alpha = 0.15f)
                        VpnStatus.ERROR -> NeonRed.copy(alpha = 0.15f)
                        else -> DarkSurfaceVariant
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        when (stats.status) {
                            VpnStatus.CONNECTED -> NeonGreen.copy(alpha = 0.4f)
                            VpnStatus.CONNECTING -> NeonAmber.copy(alpha = 0.4f)
                            VpnStatus.ERROR -> NeonRed.copy(alpha = 0.4f)
                            else -> DarkBorder
                        }
                    )
                ) {
                    Text(
                        text = when (stats.status) {
                            VpnStatus.CONNECTED -> "● STEALTH SECURED"
                            VpnStatus.CONNECTING -> "● ENGAGING CORE..."
                            VpnStatus.ERROR -> "● CORE FAILED"
                            else -> "● STANDBY"
                        },
                        color = when (stats.status) {
                            VpnStatus.CONNECTED -> NeonGreen
                            VpnStatus.CONNECTING -> NeonAmber
                            VpnStatus.ERROR -> NeonRed
                            else -> TextSecondary
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Disguise Profile Badge
                Surface(
                    modifier = Modifier.clickable { onNavigateToStealth() },
                    shape = RoundedCornerShape(8.dp),
                    color = NeonPurple.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.4f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${stealthSettings.activeProfile.title} (${stealthSettings.activeProfile.stealthScore}%)",
                            color = NeonPurple,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Central Hologram Globe & Fractal Route Lines
            CyberGlobeMapView(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                fragmentationLevel = stealthSettings.fractalFragmentationLevel,
                isConnected = isConnected,
                speedBps = stats.downloadSpeedBps
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Energy Core Power Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        when {
                            isConnected -> Brush.radialGradient(listOf(NeonGreen.copy(alpha = 0.35f), Color.Transparent))
                            isConnecting -> Brush.radialGradient(listOf(NeonAmber.copy(alpha = 0.35f), Color.Transparent))
                            else -> Brush.radialGradient(listOf(NeonCyan.copy(alpha = 0.18f), Color.Transparent))
                        }
                    )
                    .clickable { onConnectClick() }
            ) {
                Surface(
                    modifier = Modifier.size(62.dp),
                    shape = CircleShape,
                    color = when {
                        isConnected -> NeonGreen
                        isConnecting -> NeonAmber
                        else -> DarkSurfaceVariant
                    },
                    border = BorderStroke(2.dp, if (isConnected) NeonGreen else NeonCyan)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = "Toggle VPN",
                            tint = if (isConnected || isConnecting) Color(0xFF090A0F) else NeonCyan,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Holographic Speedometer & Metrics
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CockpitMetric(
                    label = "SESSION",
                    value = formatDuration(stats.durationSeconds)
                )
                CockpitMetric(
                    label = "DOWN SPEED",
                    value = formatSpeed(stats.downloadSpeedBps)
                )
                CockpitMetric(
                    label = "UP SPEED",
                    value = formatSpeed(stats.uploadSpeedBps)
                )
            }
        }
    }
}

@Composable
private fun CockpitMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 9.sp, color = TextTertiary, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(
            text = value,
            fontSize = 12.sp,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) "%02d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> "%.1f MB/s".format(mb)
        kb >= 1.0 -> "%.0f KB/s".format(kb)
        else -> "${bytesPerSec} B/s"
    }
}
