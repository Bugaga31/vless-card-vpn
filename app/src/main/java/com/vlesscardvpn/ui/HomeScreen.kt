package com.vlesscardvpn.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import java.util.Locale

@Composable
fun HomeScreen(
    repo: AppRepository,
    autoPilotEngine: AutoPilotEngine,
    vpnStats: VpnSessionStats,
    onToggleConnect: (VlessConfig?) -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToAutopilot: () -> Unit,
    onNavigateToDiagnostic: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPanicTrigger: () -> Unit
) {
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val settings by repo.settingsFlow.collectAsState()
    val autopilot by autoPilotEngine.state.collectAsState()
    val connected = vpnStats.status == VpnStatus.CONNECTED
    val connecting = vpnStats.status == VpnStatus.CONNECTING
    val stopping = vpnStats.status == VpnStatus.STOPPING
    val selected = if (connected || connecting) vpnStats.activeConfig else
        configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
    var details by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme

    // Pulsing HUD glow animation when connected or connecting
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "hud_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse_glow"
    )

    val status = when (vpnStats.status) {
        VpnStatus.CONNECTED -> "Подключено · Туннель защищён"
        VpnStatus.CONNECTING -> "Подключаем узел связи…"
        VpnStatus.STOPPING -> "Отключаем…"
        VpnStatus.ERROR -> "Не удалось подключиться"
        VpnStatus.DISCONNECTED -> "Готовы к подключению"
    }

    val glowBorderColor = when {
        connected -> com.vlesscardvpn.ui.theme.SemanticGreen.copy(alpha = pulseAlpha)
        connecting -> colors.primary.copy(alpha = pulseAlpha)
        vpnStats.status == VpnStatus.ERROR -> colors.error.copy(alpha = 0.8f)
        else -> colors.primary.copy(alpha = 0.25f)
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            brush = Brush.linearGradient(
                                listOf(colors.primaryContainer, com.vlesscardvpn.ui.theme.MineralSurfaceElevated)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .border(1.dp, colors.primary.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        "VLESS CARD",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Личное пространство связи",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateToAutopilot, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = "Управление автопилотом", tint = colors.primary)
                }
                IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Settings, contentDescription = "Параметры связи", tint = colors.onSurfaceVariant)
                }
            }
        }

        // Hero Card with Breathing Glow Border
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colors.surface,
            border = BorderStroke(if (connected || connecting) 1.5.dp else 1.dp, glowBorderColor)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            if (connected) listOf(com.vlesscardvpn.ui.theme.SemanticGreenBg.copy(alpha = 0.35f), colors.surface)
                            else listOf(colors.primaryContainer.copy(alpha = 0.8f), colors.surface)
                        )
                    )
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (connected) com.vlesscardvpn.ui.theme.SemanticGreen
                                    else if (connecting) colors.primary
                                    else colors.onSurfaceVariant.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Text(
                            "PRIVATE LOUNGE",
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 2.sp,
                            color = if (connected) com.vlesscardvpn.ui.theme.SemanticGreen else colors.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Icon(
                        if (connected) Icons.Default.Lock else Icons.Default.VpnKey,
                        contentDescription = null,
                        tint = if (connected) com.vlesscardvpn.ui.theme.SemanticGreen else colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    "Связь.\nВ вашем ритме.",
                    fontFamily = FontFamily.Serif,
                    fontSize = 32.sp,
                    lineHeight = 38.sp,
                    color = colors.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        status,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (vpnStats.status == VpnStatus.ERROR) colors.error
                                else if (connected) com.vlesscardvpn.ui.theme.SemanticGreen
                                else colors.onSurface
                    )
                    Text(
                        when {
                            connected -> "Туннель активен. Трафик защищён через Reality."
                            connecting -> "Запускаем ядро (v2ray / sing-box) и верифицируем пинг..."
                            stopping -> "Завершаем текущую сессию..."
                            vpnStats.status == VpnStatus.ERROR -> vpnStats.errorMessage ?: "Проверьте сервер или откройте диагностику."
                            selected == null -> "Добавьте свой узел связи, чтобы начать."
                            else -> "Выбран узел: ${selected.name.ifBlank { selected.address }}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }

                // Connect / Disconnect Action Button (True 1-Click: auto-fetches & connects if empty)
                Button(
                    onClick = { onToggleConnect(selected) },
                    enabled = !stopping,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connected) com.vlesscardvpn.ui.theme.MineralSurfaceElevated else colors.primary,
                        contentColor = if (connected) com.vlesscardvpn.ui.theme.SemanticRed else colors.onPrimary
                    ),
                    border = if (connected) BorderStroke(1.dp, com.vlesscardvpn.ui.theme.SemanticRed.copy(alpha = 0.5f)) else null
                ) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (connected) Icons.Default.PowerSettingsNew else Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            connected -> "Отключить туннель"
                            connecting -> "Отменить подключение"
                            stopping -> "Отключение…"
                            selected == null -> "Подключить автоматически (1-Click)"
                            else -> "Подключиться"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // Selected Server Tile with Protocol Badge
        Surface(
            onClick = onNavigateToServers,
            shape = RoundedCornerShape(20.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(colors.surfaceVariant, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = if (connected) com.vlesscardvpn.ui.theme.SemanticGreen else colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("УЗЕЛ СВЯЗИ", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        if (selected != null) {
                            Box(
                                modifier = Modifier
                                    .background(colors.primaryContainer.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    selected.protocolType.uppercase(Locale.ROOT),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = colors.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        selected?.name?.ifBlank { selected.address } ?: "Выберите направление",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        selected?.let {
                            val country = if (it.country.isNotBlank()) " · ${it.country}" else ""
                            val ping = if (it.pingMs > 0) " · ${it.pingMs} мс" else ""
                            "${it.address}:${it.port}$country$ping"
                        } ?: "Импорт конфигурации и список серверов",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }

        // Live Speed and Traffic Telemetry Row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoungeMetricCard(
                label = "СКОРОСТЬ ↓",
                value = if (connected) formatSpeed(vpnStats.downloadSpeedBps) else "0 КБ/с",
                subValue = if (connected) "↑ ${formatSpeed(vpnStats.uploadSpeedBps)}" else "—",
                modifier = Modifier.weight(1.1f)
            )
            LoungeMetricCard(
                label = "ВРЕМЯ",
                value = if (connected) duration(vpnStats.durationSeconds) else "00:00:00",
                subValue = if (connected) "онлайн" else "—",
                modifier = Modifier.weight(0.95f)
            )
            LoungeMetricCard(
                label = "ТРАФИК",
                value = if (connected) bytes(vpnStats.bytesIn + vpnStats.bytesOut) else "0 МБ",
                subValue = if (connected) "всего" else "—",
                modifier = Modifier.weight(0.95f)
            )
        }
        OutlinedButton(onClick = onNavigateToAutopilot, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (autopilot.isEnabled) "Автопилот · управление" else "Настроить автопилот")
        }
        TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (details) "Скрыть детали соединения" else "Маршрутизация и диагностика")
            Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (details) {
            Text("Правила маршрутизации", style = MaterialTheme.typography.titleMedium)
            Text(if (settings.enableRuDirect) "Для части российских сайтов и локальных адресов настроен прямой маршрут без VPN."
                else "Правила прямых маршрутов и блокировок проверяйте для конкретного адреса.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            OutlinedTextField(value = target, onValueChange = { target = it },
                label = { Text("Домен или IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (target.isNotBlank()) {
                val match = DiagnosticEngine.inspectRouteDecision(target, settings)
                Text(match.explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("Это оценка правил, не проверка фактического трафика.", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            OutlinedButton(onClick = onNavigateToDiagnostic, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Открыть диагностику")
            }
        }
        Text("PRIVATE LOUNGE · VLESS CARD", style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, letterSpacing = 2.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun LoungeMetricCard(label: String, value: String, subValue: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                subValue,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec <= 0 -> "0 КБ/с"
    bytesPerSec < 1024 -> "$bytesPerSec Б/с"
    bytesPerSec < 1048576 -> String.format(Locale.ROOT, "%.1f КБ/с", bytesPerSec / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f МБ/с", bytesPerSec / 1048576.0)
}

private fun duration(seconds: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
private fun bytes(value: Long): String = when {
    value < 1024 -> "$value Б"
    value < 1048576 -> String.format(Locale.ROOT, "%.1f КБ", value / 1024.0)
    value < 1073741824 -> String.format(Locale.ROOT, "%.1f МБ", value / 1048576.0)
    else -> String.format(Locale.ROOT, "%.2f ГБ", value / 1073741824.0)
}
