package com.vlesscardvpn.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.domain.AutoPilotStatus
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutopilotScreen(
    repo: AppRepository,
    autoPilotEngine: AutoPilotEngine,
    onBack: () -> Unit
) {
    val settings by repo.settingsFlow.collectAsState()
    val state by autoPilotEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("⚡ Autonomous Autopilot", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
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
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Main Autopilot Activation Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, if (state.isEnabled) NeonGreen.copy(alpha = 0.5f) else DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Autopilot Engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Periodically checks servers, switches to fastest responsive node and auto-recovers on 3 errors.",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        Switch(
                            checked = state.isEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    autoPilotEngine.startAutoPilot(settings.healthCheckInterval)
                                    repo.updateSettings { it.copy(autoSelect = true) }
                                } else {
                                    autoPilotEngine.stopAutoPilot()
                                    repo.updateSettings { it.copy(autoSelect = false) }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonGreen,
                                checkedTrackColor = NeonGreen.copy(alpha = 0.3f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STATUS: ${state.status.name}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (state.status) {
                                AutoPilotStatus.ACTIVE -> NeonGreen
                                AutoPilotStatus.SWITCHING, AutoPilotStatus.SCANNING -> NeonAmber
                                AutoPilotStatus.ERROR -> NeonRed
                                else -> TextTertiary
                            }
                        )

                        Button(
                            onClick = {
                                scope.launch { autoPilotEngine.triggerManualScan() }
                            },
                            enabled = !state.isEvaluating,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (state.isEvaluating) "Testing..." else "Run Check Now", fontSize = 11.sp, color = NeonCyan)
                        }
                    }
                }
            }

            // Engine Controls & Interval Settings
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AUTOPILOT PARAMETERS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonCyan)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Health Check Interval", fontSize = 13.sp, color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15, 30, 60).forEach { sec ->
                                FilterChip(
                                    selected = settings.healthCheckInterval == sec,
                                    onClick = {
                                        repo.updateSettings { it.copy(healthCheckInterval = sec) }
                                        if (state.isEnabled) {
                                            autoPilotEngine.startAutoPilot(sec)
                                        }
                                    },
                                    label = { Text("${sec}s", fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto Failover (3 strikes rule)", fontSize = 13.sp, color = TextPrimary)
                            Text("Automatically reconnects after 3 confirmed tunnel drops", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.failoverEnabled,
                            onCheckedChange = { repo.updateSettings { s -> s.copy(failoverEnabled = it) } },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.3f))
                        )
                    }
                }
            }

            // Real-time Event Console Log
            Text("LIVE TELEMETRY LOGS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF07090E),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                if (state.logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No autopilot events recorded yet.", fontSize = 12.sp, color = TextTertiary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        reverseLayout = true
                    ) {
                        items(state.logs.reversed()) { logEntry ->
                            Text(
                                text = logEntry,
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = when {
                                    logEntry.contains("[FAIL]") || logEntry.contains("[FAILOVER]") -> NeonRed
                                    logEntry.contains("[SWITCH]") -> NeonCyan
                                    logEntry.contains("[AUTOPILOT]") -> NeonGreen
                                    else -> TextSecondary
                                },
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
