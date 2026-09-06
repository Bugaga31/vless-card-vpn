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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.domain.AutoPilotStatus
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Precision Autopilot Screen:
 * Autonomous failover engine configuration & live event telemetry.
 */
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Автопилот и восстановление",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
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
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space12),
            verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
        ) {
            // Main Switch Card
            Surface(
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (state.isEnabled) SemanticGreen.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(InstrumentDimens.space16)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Автономный мониторинг туннеля",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Периодически проверяет целостность связи и переключает на резервный узел при 3 подтверждённых ошибках.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                checkedThumbColor = SignalOrange,
                                checkedTrackColor = SignalOrangeContainer
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(InstrumentDimens.space12))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "СОСТОЯНИЕ: ${state.status.name}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = when (state.status) {
                                AutoPilotStatus.ACTIVE -> SemanticGreen
                                AutoPilotStatus.SWITCHING, AutoPilotStatus.SCANNING -> SignalOrange
                                AutoPilotStatus.ERROR -> SemanticRed
                                else -> GraphiteTertiary
                            }
                        )

                        Button(
                            onClick = { scope.launch { autoPilotEngine.triggerManualScan() } },
                            enabled = !state.isEvaluating,
                            shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                            colors = ButtonDefaults.buttonColors(containerColor = MineralSurfaceSubtle, contentColor = MaterialTheme.colorScheme.onBackground),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (state.isEvaluating) "Проверка..." else "Проверить сейчас", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Interval Settings
            Surface(
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(InstrumentDimens.space16), verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space12)) {
                    Text(
                        text = "ИНТЕРВАЛ ОПРОСА СЕРВЕРОВ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                    ) {
                        listOf(15, 30, 60).forEach { sec ->
                            FilterChip(
                                selected = settings.healthCheckInterval == sec,
                                onClick = {
                                    repo.updateSettings { it.copy(healthCheckInterval = sec) }
                                    if (state.isEnabled) {
                                        autoPilotEngine.startAutoPilot(sec)
                                    }
                                },
                                label = { Text("${sec} сек", style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SignalOrangeContainer,
                                    selectedLabelColor = SignalOrangeContent
                                )
                            )
                        }
                    }
                }
            }

            // Telemetry Log List
            Text(
                text = "ЖУРНАЛ СОБЫТИЙ",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                if (state.logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Событий не зафиксировано.", style = MaterialTheme.typography.bodySmall, color = GraphiteTertiary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(InstrumentDimens.space12),
                        reverseLayout = true
                    ) {
                        items(state.logs.reversed()) { logEntry ->
                            Text(
                                text = logEntry,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    logEntry.contains("[FAIL]") || logEntry.contains("[FAILOVER]") -> SemanticRed
                                    logEntry.contains("[SWITCH]") -> SignalOrange
                                    logEntry.contains("[AUTOPILOT]") -> SemanticGreen
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
