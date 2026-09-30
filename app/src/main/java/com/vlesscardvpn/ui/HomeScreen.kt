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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.ui.theme.SemanticGreen
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import java.util.Locale

/** Static, scrollable control panel: no permanent animation loop on the home screen. */
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
    onPanicTrigger: () -> Unit,
    preparingConnection: Boolean = false,
    onNavigateToCrashReports: () -> Unit = {}
) {
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val settings by repo.settingsFlow.collectAsState()
    val autopilot by autoPilotEngine.state.collectAsState()
    val connected = vpnStats.status == VpnStatus.CONNECTED
    val connecting = vpnStats.status == VpnStatus.CONNECTING || preparingConnection
    val stopping = vpnStats.status == VpnStatus.STOPPING
    val selected = if (connected || vpnStats.status == VpnStatus.CONNECTING) vpnStats.activeConfig else
        configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
    val colors = MaterialTheme.colorScheme
    var details by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    val status = when {
        preparingConnection -> "Подбираем сервер"
        connected -> "Туннель запущен"
        connecting -> "Подключение"
        stopping -> "Отключение"
        vpnStats.status == VpnStatus.ERROR -> "Нужна проверка"
        else -> "Готов к подключению"
    }
    val statusColor = when {
        connected -> SemanticGreen
        vpnStats.status == VpnStatus.ERROR -> colors.error
        else -> colors.primary
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VLESS / CARD", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Ваш центр подключения", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Настройки")
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = colors.surface,
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.65f))) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(if (connected) Icons.Default.Shield else Icons.Default.VpnKey,
                    contentDescription = null, tint = statusColor, modifier = Modifier.size(40.dp))
                Text(status, style = MaterialTheme.typography.titleLarge, color = statusColor)
                Text(when {
                    preparingConnection -> "Загружаем подписки и проверяем доступность узлов. Поиск можно отменить."
                    connecting -> "Проверяем конфигурацию и запускаем ядро."
                    connected -> "${selected?.protocolType?.uppercase(Locale.ROOT) ?: "VPN"} · ${selected?.security?.uppercase(Locale.ROOT) ?: "—"}. Доступность сайтов проверьте в диагностике."
                    vpnStats.status == VpnStatus.ERROR -> vpnStats.errorMessage ?: "Откройте отчёт об ошибке или проверьте сервер."
                    else -> "Выберите свой сервер или запустите автоматический подбор."
                }, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                Button(onClick = { onToggleConnect(if (settings.autoSelect) null else selected) },
                    enabled = !stopping,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                    if (connecting) CircularProgressIndicator(Modifier.size(20.dp), color = colors.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(when {
                        connected -> "Отключить"
                        connecting -> "Отменить"
                        stopping -> "Отключение…"
                        selected == null || settings.autoSelect -> "Подключить автоматически"
                        else -> "Подключить"
                    }, fontWeight = FontWeight.SemiBold)
                }
                if (vpnStats.status == VpnStatus.ERROR && !preparingConnection) {
                    TextButton(onClick = onNavigateToCrashReports, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Открыть отчёты об ошибках")
                    }
                }
            }
        }
        Surface(onClick = onNavigateToServers, shape = RoundedCornerShape(16.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outline)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Public, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Сервер", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(selected?.name?.ifBlank { selected.address } ?: "Добавить или выбрать",
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(selected?.let { if (it.pingMs > 0) "Доступность узла: ${it.pingMs} мс" else "Доступность не проверена" }
                        ?: "${configs.size} конфигураций", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        // Two columns, not three cramped gauges. Text wraps at large system font sizes.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectionMetric("Загрузка ↓", if (connected) formatSpeed(vpnStats.downloadSpeedBps) else "—",
                if (connected) "↑ ${formatSpeed(vpnStats.uploadSpeedBps)}" else "Нет активной сессии", Modifier.weight(1f))
            ConnectionMetric("Время", if (connected) duration(vpnStats.durationSeconds) else "—",
                if (connected) bytes(vpnStats.bytesIn + vpnStats.bytesOut) else "Трафик: —", Modifier.weight(1f))
        }
        OutlinedButton(onClick = onNavigateToAutopilot, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (autopilot.isEnabled) "Автопилот включён · управление" else "Настроить автопилот")
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Стабильный TLS", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
                Text("SNI и отпечаток из конфигурации, без случайной подмены и экспериментальной фрагментации. Проверка сертификата включена.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
                if (settings.evasionStrategy == "stable_tls" && !settings.enableFragmentation && !settings.enableSniRotation) {
                    Text("Профиль выбран · применяется при следующем подключении", style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimaryContainer)
                } else {
                    OutlinedButton(onClick = {
                        repo.updateSettings { it.copy(evasionStrategy = "stable_tls", enableFragmentation = false,
                            enableSniRotation = false, customSniOverride = "auto") }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Применить профиль") }
                }
                Text("Эффективность зависит от сервера и сети. Обход блокировок не гарантирован.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
            }
        }
        OutlinedButton(onClick = onNavigateToDiagnostic, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Проверить подключение")
        }
        TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (details) "Скрыть маршруты" else "Маршруты и исключения")
        }
        if (details) {
            Text(if (settings.enableRuDirect) "Часть российских сайтов и локальные адреса идут напрямую, без VPN."
                else "Для локальных адресов и выбранных приложений могут действовать исключения.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            OutlinedTextField(value = target, onValueChange = { target = it.take(253) }, label = { Text("Домен или IP") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (target.isNotBlank()) {
                val match = DiagnosticEngine.inspectRouteDecision(target, settings)
                Text(match.explanation, style = MaterialTheme.typography.bodyMedium)
                Text("Оценка правил, не проверка фактического трафика.", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
        TextButton(onClick = onPanicTrigger, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Отключить VPN и закрыть приложение", color = colors.error)
        }
    }
}

@Composable
private fun ConnectionMetric(label: String, value: String, detail: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
