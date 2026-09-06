package com.vlesscardvpn.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.RouteDiagramView
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus

/**
 * Precision / Field Instrument Home Screen:
 * 1. Compact header with "VLESS CARD" & Settings.
 * 2. Prominent real status: "Не подключено", "Подключаем…", "Подключено", "Ошибка подключения".
 * 3. Exact schematic route diagram: "Устройство → Туннель → Сервер".
 * 4. Selected server details (Name, Address, Port, SNI, Latency).
 * 5. Primary Action Button (Connect / Disconnect / Cancel) in Signal Orange.
 * 6. Authentic real-time telemetry: Duration, Download, Upload.
 * 7. Active routing flags: "RU напрямую" and "Блок QUIC".
 */
@Composable
fun HomeScreen(
    repo: AppRepository,
    autoPilotEngine: AutoPilotEngine,
    vpnStats: VpnSessionStats,
    onToggleConnect: (VlessConfig?) -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToAutopilot: () -> Unit,
    onNavigateToDiagnostic: () -> Unit,
    onPanicTrigger: () -> Unit
) {
    val context = LocalContext.current
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val settings by repo.settingsFlow.collectAsState()
    val activeConfig = configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
    val isConnected = vpnStats.status == VpnStatus.CONNECTED
    val isConnecting = vpnStats.status == VpnStatus.CONNECTING
    val isError = vpnStats.status == VpnStatus.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = InstrumentDimens.space24, vertical = InstrumentDimens.space16)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
    ) {
        // 1. Header: VLESS CARD brand mark + Quick Diagnostic / Autopilot
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = InstrumentDimens.space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(SignalOrange)
                )
                Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                Text(
                    text = "VLESS CARD",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    letterSpacing = 1.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)) {
                // Autopilot Status Badge
                Surface(
                    color = if (settings.autoSelect) SemanticGreenBg else MineralSurfaceSubtle,
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    border = BorderStroke(1.dp, if (settings.autoSelect) SemanticGreen.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline),
                    modifier = Modifier.clickable { onNavigateToAutopilot() }
                ) {
                    Text(
                        text = if (settings.autoSelect) "АВТОПИЛОТ" else "РУЧНОЙ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.autoSelect) SemanticGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                IconButton(
                    onClick = onNavigateToDiagnostic,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = "Диагностика",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(InstrumentDimens.space4))

        // 2. Prominent Real Status Header
        Column {
            Text(
                text = when (vpnStats.status) {
                    VpnStatus.CONNECTED -> "Подключено"
                    VpnStatus.CONNECTING -> "Подключаем…"
                    VpnStatus.STOPPING -> "Отключение…"
                    VpnStatus.ERROR -> "Ошибка подключения"
                    VpnStatus.DISCONNECTED -> "Не подключено"
                },
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = when (vpnStats.status) {
                    VpnStatus.CONNECTED -> SemanticGreen
                    VpnStatus.CONNECTING -> SignalOrange
                    VpnStatus.ERROR -> SemanticRed
                    else -> MaterialTheme.colorScheme.onBackground
                }
            )

            Text(
                text = when (vpnStats.status) {
                    VpnStatus.CONNECTED -> "Туннель активен • Трафик защищён"
                    VpnStatus.CONNECTING -> "Инициализация ядра и проверка маршрутизации..."
                    VpnStatus.ERROR -> vpnStats.errorMessage ?: "Целевой узел недоступен"
                    else -> "Выберите узел и нажмите кнопку запуска"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 3. Precision Route Diagram: "Устройство → Туннель → Сервер"
        RouteDiagramView(
            vpnStatus = vpnStats.status,
            activeConfig = activeConfig,
            networkTypeName = if (settings.enableRuDirect) "Прямой RU" else "Обычный"
        )

        // 4. Selected Server Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToServers() },
            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (isConnected) SemanticGreen.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(InstrumentDimens.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ВЫБРАННЫЙ СЕРВЕР",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(InstrumentDimens.space4))
                    Text(
                        text = activeConfig?.name ?: "Сервер не выбран",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = if (activeConfig != null) "${activeConfig.address}:${activeConfig.port} • ${activeConfig.protocolType.uppercase()}" else "Нажмите для выбора сервера из списка",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activeConfig != null && activeConfig.pingMs > 0) {
                        Surface(
                            color = SemanticGreenBg,
                            shape = RoundedCornerShape(InstrumentDimens.radiusSmall)
                        ) {
                            Text(
                                text = "${activeConfig.pingMs} мс",
                                color = SemanticGreen,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Выбрать",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. Main Primary Action Button (<= 10% screen color accent)
        Button(
            onClick = { onToggleConnect(activeConfig) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) SemanticRed else SignalOrange,
                contentColor = Color.White
            )
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                Text(
                    text = "Отменить подключение",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Text(
                    text = if (isConnected) "Отключить туннель" else "Подключить",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 6. Compact Authentic Telemetry (Duration, Download, Upload)
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
                TelemetryCell(
                    label = "ВРЕМЯ",
                    value = if (isConnected) formatDuration(vpnStats.durationSeconds) else "—"
                )
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                TelemetryCell(
                    label = "ПРИЁМ",
                    value = if (isConnected && vpnStats.bytesIn > 0) formatBytes(vpnStats.bytesIn) else if (isConnected) "0 КБ" else "Нет данных"
                )
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                TelemetryCell(
                    label = "ПЕРЕДАЧА",
                    value = if (isConnected && vpnStats.bytesOut > 0) formatBytes(vpnStats.bytesOut) else if (isConnected) "0 КБ" else "Нет данных"
                )
            }
        }

        // 7. Active Routing Rules Summary Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
        ) {
            if (settings.enableRuDirect) {
                Surface(
                    color = MineralSurfaceSubtle,
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SemanticGreen))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RU напрямую (0 мс)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (settings.blockQuicYouTube) {
                Surface(
                    color = MineralSurfaceSubtle,
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SignalOrange))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "QUIC блокировка (YouTube)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(InstrumentDimens.space12))
    }
}

@Composable
private fun TelemetryCell(label: String, value: String) {
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

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val hrs = mins / 60
    val remMins = mins % 60
    val remSecs = seconds % 60
    return if (hrs > 0) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", hrs, remMins, remSecs)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", remMins, remSecs)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f МБ", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.2f ГБ", gb)
}
