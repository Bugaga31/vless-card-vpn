package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
    val status = when (vpnStats.status) {
        VpnStatus.CONNECTED -> "Подключено"
        VpnStatus.CONNECTING -> "Подключаем…"
        VpnStatus.STOPPING -> "Отключаем…"
        VpnStatus.ERROR -> "Не удалось подключиться"
        VpnStatus.DISCONNECTED -> "Готовы к подключению"
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VLESS CARD", style = MaterialTheme.typography.labelMedium,
                    color = colors.primary, letterSpacing = 3.sp)
                Text("Ваше личное пространство", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            IconButton(onClick = onNavigateToAutopilot, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "Управление автопилотом", tint = colors.primary)
            }
        }

        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.3f))) {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surface)))
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("PRIVATE LOUNGE", style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 2.sp, color = colors.primary)
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = colors.primary)
                }
                Text("Связь.\nВ вашем ритме.", fontFamily = FontFamily.Serif,
                    fontSize = 34.sp, lineHeight = 40.sp, color = colors.onSurface)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, style = MaterialTheme.typography.titleMedium,
                        color = if (vpnStats.status == VpnStatus.ERROR) colors.error else colors.onSurface)
                    Text(when {
                        connected -> "Туннель активен. Исключения маршрутизации — ниже."
                        connecting -> "Запускаем туннель и проверяем соединение."
                        stopping -> "Завершаем текущую сессию."
                        vpnStats.status == VpnStatus.ERROR -> vpnStats.errorMessage ?: "Проверьте сервер или откройте диагностику."
                        selected == null -> "Добавьте свой сервер, чтобы начать."
                        else -> "Ваш сервер выбран. Осталось одно касание."
                    }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Button(
                    onClick = { if (selected == null && !connecting && !connected) onNavigateToServers() else onToggleConnect(selected) },
                    enabled = !stopping,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                ) {
                    if (connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    } else Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(when {
                        connected -> "Отключить"
                        connecting -> "Отменить подключение"
                        stopping -> "Отключение…"
                        selected == null -> "Добавить сервер"
                        else -> "Подключиться"
                    }, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Surface(onClick = onNavigateToServers, shape = RoundedCornerShape(20.dp),
            color = colors.surface, border = BorderStroke(1.dp, colors.outline)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ВАШ СЕРВЕР", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(selected?.name ?: "Выберите направление", style = MaterialTheme.typography.titleMedium)
                    Text(selected?.let { "${it.protocolType.uppercase(Locale.ROOT)} · ${it.country}" }
                        ?: "Импорт конфигурации и список серверов", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoungeMetric("СЕССИЯ", if (connected) duration(vpnStats.durationSeconds) else "—", Modifier.weight(1f))
            LoungeMetric("ПОЛУЧЕНО", if (connected) bytes(vpnStats.bytesIn) else "—", Modifier.weight(1f))
            LoungeMetric("ОТПРАВЛЕНО", if (connected) bytes(vpnStats.bytesOut) else "—", Modifier.weight(1f))
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
private fun LoungeMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun duration(seconds: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
private fun bytes(value: Long): String = when {
    value < 1024 -> "$value Б"
    value < 1048576 -> String.format(Locale.ROOT, "%.1f КБ", value / 1024.0)
    value < 1073741824 -> String.format(Locale.ROOT, "%.1f МБ", value / 1048576.0)
    else -> String.format(Locale.ROOT, "%.1f ГБ", value / 1073741824.0)
}
