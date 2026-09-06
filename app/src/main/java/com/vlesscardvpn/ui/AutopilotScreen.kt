package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Precision Network Lab Screen («Лаборатория сети» / «Автопилот сети»):
 * 1. Master switch & honest mission explanation.
 * 2. Visual Conveyor "Схема работы": [ Сеть → Профиль → Туннель → Проверка ].
 * 3. Active Profile & Reason for selection.
 * 4. Accurate Telemetry (Success rate %, HTTPS latency, variance, reconnects).
 * 5. Actions: "Проверить сейчас", "Вернуть рабочий профиль", "Сбросить обучение".
 * 6. Authentic Event Log.
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
    var showConsentDialog by remember { mutableStateOf(false) }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = {
                Text(
                    text = "Согласие на фоновые проверки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Автопилот сети выполняет легковесные проверочные HTTPS-запросы к тестовым узлам (generate_204) для подтверждения работоспособности туннеля. " +
                            "Это расходует минимальный объем трафика (менее 1 КБ на проверку). " +
                            "Личные данные и содержимое пакетов не передаются.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        autoPilotEngine.setConsent(true)
                        repo.updateSettings { it.copy(autopilotConsentGiven = true, autoSelect = true) }
                        autoPilotEngine.startAutoPilot(settings.healthCheckInterval)
                        showConsentDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
                ) {
                    Text("Разрешить и включить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Лаборатория сети",
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
                actions = {
                    IconButton(onClick = { autoPilotEngine.resetLearnedMemory() }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Сбросить обучение", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            // 1. Main Master Card
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
                                text = "Автопилот сети",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Подбирает совместимые настройки и восстанавливает подключение. Не гарантирует обход блокировок.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = state.isEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    if (!state.consentGiven && !settings.autopilotConsentGiven) {
                                        showConsentDialog = true
                                    } else {
                                        repo.updateSettings { it.copy(autoSelect = true) }
                                        autoPilotEngine.startAutoPilot(settings.healthCheckInterval)
                                    }
                                } else {
                                    repo.updateSettings { it.copy(autoSelect = false) }
                                    autoPilotEngine.stopAutoPilot()
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (state.status) {
                                            AutopilotStateStatus.STABLE -> SemanticGreen
                                            AutopilotStateStatus.CHECKING, AutopilotStateStatus.ADAPTING -> SignalOrange
                                            AutopilotStateStatus.RECOVERING -> SemanticAmber
                                            AutopilotStateStatus.NEEDS_HELP -> SemanticRed
                                            AutopilotStateStatus.DISABLED -> GraphiteTertiary
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (state.status) {
                                    AutopilotStateStatus.STABLE -> "СТАБИЛЬНО"
                                    AutopilotStateStatus.CHECKING -> "ПРОВЕРКА"
                                    AutopilotStateStatus.ADAPTING -> "ПОДБОР"
                                    AutopilotStateStatus.RECOVERING -> "ВОССТАНОВЛЕНИЕ"
                                    AutopilotStateStatus.NEEDS_HELP -> "НУЖНА ПОМОЩЬ"
                                    AutopilotStateStatus.DISABLED -> "ВЫКЛЮЧЕН"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Button(
                            onClick = { scope.launch { autoPilotEngine.triggerManualScan() } },
                            enabled = !state.isBusy && state.isEnabled,
                            shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                            colors = ButtonDefaults.buttonColors(containerColor = MineralSurfaceSubtle, contentColor = MaterialTheme.colorScheme.onBackground),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(if (state.isBusy) "Проверка..." else "Проверить сейчас", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 2. Conveyor "Схема работы": [ Сеть → Профиль → Туннель → Проверка ]
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(InstrumentDimens.space16)) {
                    Text(
                        text = "СХЕМА РАБОТЫ",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.8.sp
                    )

                    Spacer(modifier = Modifier.height(InstrumentDimens.space12))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PipelineNode(
                            name = "1. Сеть",
                            health = state.stepHealthMap[PipelineStep.NETWORK] ?: StepHealth.IDLE,
                            isCurrent = state.currentStep == PipelineStep.NETWORK
                        )
                        PipelineArrow()
                        PipelineNode(
                            name = "2. Профиль",
                            health = state.stepHealthMap[PipelineStep.PROFILE] ?: StepHealth.IDLE,
                            isCurrent = state.currentStep == PipelineStep.PROFILE
                        )
                        PipelineArrow()
                        PipelineNode(
                            name = "3. Туннель",
                            health = state.stepHealthMap[PipelineStep.TUNNEL] ?: StepHealth.IDLE,
                            isCurrent = state.currentStep == PipelineStep.TUNNEL
                        )
                        PipelineArrow()
                        PipelineNode(
                            name = "4. Проверка",
                            health = state.stepHealthMap[PipelineStep.VERIFICATION] ?: StepHealth.IDLE,
                            isCurrent = state.currentStep == PipelineStep.VERIFICATION
                        )
                    }

                    Spacer(modifier = Modifier.height(InstrumentDimens.space8))
                    Text(
                        text = state.lastChangeExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. Honest Telemetry Bar (Success Rate, HTTPS Latency, Variance, Reconnects)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(InstrumentDimens.space12),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricCell(
                        label = "УСПЕШНОСТЬ",
                        value = "${state.checkSuccessRatePercent}%"
                    )
                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                    MetricCell(
                        label = "HTTPS ОТКЛИК",
                        value = if (state.lastHttpsLatencyMs > 0) "${state.lastHttpsLatencyMs} мс" else "—"
                    )
                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                    MetricCell(
                        label = "РАЗБРОС",
                        value = if (state.latencyVarianceMs > 0) "±${state.latencyVarianceMs} мс" else "0 мс"
                    )
                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                    MetricCell(
                        label = "ПЕРЕЗАПУСКОВ",
                        value = "${state.reconnectCount}"
                    )
                }
            }

            // 4. Action Buttons Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
            ) {
                OutlinedButton(
                    onClick = { scope.launch { autoPilotEngine.restoreLastWorkingProfile() } },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Вернуть рабочий профиль", style = MaterialTheme.typography.bodySmall)
                }
            }

            // 5. Authentic Telemetry Event Log
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
                if (state.eventLogs.isEmpty()) {
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
                        items(state.eventLogs.reversed()) { logEntry ->
                            Text(
                                text = logEntry,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    logEntry.contains("Сбой") || logEntry.contains("Внимание") -> SemanticRed
                                    logEntry.contains("Выбран") || logEntry.contains("Восстановление") -> SignalOrange
                                    logEntry.contains("запущен") || logEntry.contains("подтверждён") -> SemanticGreen
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

@Composable
private fun PipelineNode(name: String, health: StepHealth, isCurrent: Boolean) {
    val color = when (health) {
        StepHealth.SUCCESS -> SemanticGreen
        StepHealth.IN_PROGRESS -> SignalOrange
        StepHealth.WARNING -> SemanticAmber
        StepHealth.FAILURE -> SemanticRed
        StepHealth.IDLE -> GraphiteTertiary
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isCurrent) SignalOrangeContainer else MineralSurfaceSubtle)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PipelineArrow() {
    Icon(
        imageVector = Icons.Default.ArrowForward,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = GraphiteTertiary
    )
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
