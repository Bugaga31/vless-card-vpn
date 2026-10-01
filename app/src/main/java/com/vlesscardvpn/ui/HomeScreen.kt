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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import java.util.Locale

@Composable
fun HomeScreen(
    repo: AppRepository, autoPilotEngine: AutoPilotEngine, vpnStats: VpnSessionStats,
    onToggleConnect: (VlessConfig?) -> Unit, onNavigateToServers: () -> Unit,
    onNavigateToAutopilot: () -> Unit, onNavigateToDiagnostic: () -> Unit,
    onNavigateToSettings: () -> Unit, onPanicTrigger: () -> Unit,
    preparingConnection: Boolean = false, onNavigateToCrashReports: () -> Unit = {},
    onAutoConnect: () -> Unit = { onToggleConnect(null) }
) {
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val selected = vpnStats.activeConfig ?: configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
    HomeDashboard(vpnStats, selected, configs.size, preparingConnection,
        onConnect = { onToggleConnect(selected) }, onAuto = onAutoConnect,
        onServers = onNavigateToServers, onSettings = onNavigateToSettings,
        onDiagnostics = onNavigateToDiagnostic, onReports = onNavigateToCrashReports)
}

/** Pure presentational screen, shared by device tests and actual Compose snapshot rendering. */
@Composable
fun HomeDashboard(
    stats: VpnSessionStats, selected: VlessConfig? = null, configCount: Int = 0,
    preparing: Boolean = false, onConnect: () -> Unit = {}, onAuto: () -> Unit = {},
    onServers: () -> Unit = {}, onSettings: () -> Unit = {}, onDiagnostics: () -> Unit = {},
    onReports: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val working = stats.status == VpnStatus.CONNECTED && stats.health.internet
    val busy = stats.status == VpnStatus.CONNECTING || preparing
    val stopping = stats.status == VpnStatus.STOPPING
    val error = stats.status == VpnStatus.ERROR
    val success = Color(0xFF8BDFB3)
    val headline = when {
        busy -> "Ищем рабочий\nмаршрут"
        working -> "Вы в сети"
        error -> "Маршрут пока\nне найден"
        stats.status == VpnStatus.CONNECTED -> "Проверяем связь"
        else -> "Интернет без\nлишних настроек"
    }
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = colors.primaryContainer) {
                Icon(Icons.Default.Shield, null, tint = colors.primary, modifier = Modifier.padding(12.dp).size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("VLESS Card", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text("Один рабочий маршрут", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Settings, "Настройки", tint = colors.onBackground) }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = colors.surface,
            border = BorderStroke(1.dp, if (working) success.copy(alpha = .5f) else colors.outline.copy(alpha = .6f))) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (working) Icons.Default.CheckCircle else if (error) Icons.Default.Info else Icons.Default.Public,
                        null, tint = if (working) success else colors.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when { working -> "HTTPS через сервер подтверждён"; busy -> "АВТО / ПРОВЕРКА"; error -> "Нужен другой сервер"; else -> "ГОТОВ К ПОДКЛЮЧЕНИЮ" },
                        style = MaterialTheme.typography.bodyMedium, color = if (working) success else colors.onSurfaceVariant)
                }
                Text(headline, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                Text(when {
                    busy -> stats.progressMessage.ifBlank { "Проверяем конфигурацию и реальную передачу трафика" }
                    working -> "${if (stats.autoMode) "Авто" else "Ваш сервер"} · ${stats.profileLabel} · ${stats.health.latencyMs} мс"
                    error -> stats.errorMessage ?: "Сервер не прошёл сквозную проверку"
                    else -> "Нажмите «Авто». Приложение найдёт сервер и проверит HTTPS, YouTube и Telegram веб."
                }, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                ConnectionActions(stats.status == VpnStatus.CONNECTED, busy, stopping,
                    selected != null, onConnect, onAuto)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!working && !busy && !error) Text("Авто использует ваши и публичные серверы; выполняет сетевые проверки.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Surface(onClick = onServers, shape = RoundedCornerShape(16.dp), color = colors.surface) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dns, null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("СЕРВЕРЫ / $configCount", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(selected?.name?.ifBlank { "Сервер из подписки" } ?: "Добавить подписку",
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (selected != null) "${selected.protocolType.uppercase(Locale.ROOT)} · ${selected.security.uppercase(Locale.ROOT)}" else "Или доверьте подбор кнопке «Авто»",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, null, tint = colors.onSurfaceVariant)
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surface) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Проверка маршрута", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDiagnostics, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.OpenInNew, "Диагностика") }
                }
                for ((label, passed) in listOf("HTTPS через VPN" to stats.health.internet,
                    "YouTube · HTTPS" to stats.health.youtube, "Telegram · веб" to stats.health.telegram)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        val checked = stats.health.checkedAt > 0
                        Icon(if (!checked) Icons.Default.Remove else if (passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            if (!checked) "Не проверено" else if (passed) "Ожидаемый ответ получен" else "Проверка не пройдена",
                            tint = if (!checked) colors.onSurfaceVariant else if (passed) success else colors.error, modifier = Modifier.size(20.dp))
                    }
                }
                Text("Веб-проверка не гарантирует видео, MTProto или звонки.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (working) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatCell("Загрузка", speed(stats.downloadSpeedBps), Modifier.weight(1f))
            StatCell("В сети", time(stats.durationSeconds), Modifier.weight(1f))
        }
        if (error) OutlinedButton(onClick = onReports, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Отчёты об ошибках")
        }
        Text("SNI, ключи и параметры сервера сохраняются. Случайная подмена доменов отключена в «Авто».",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
    }
}
@Composable
private fun ConnectionActions(connected: Boolean, busy: Boolean, stopping: Boolean, hasServer: Boolean,
    onConnect: () -> Unit, onAuto: () -> Unit) {
    val connect: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(onClick = onConnect, enabled = !stopping && (hasServer || busy || connected),
            modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
            Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
            Text(if (busy) "Отменить" else if (connected) "Отключить" else "Подключить", fontSize = 14.sp)
        }
    }
    val auto: @Composable (Modifier) -> Unit = { modifier ->
        Button(onClick = onAuto, enabled = !busy && !stopping,
            modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text("Авто", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 240.dp || LocalDensity.current.fontScale > 1.15f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { auto(Modifier.fillMaxWidth()); connect(Modifier.fillMaxWidth()) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { connect(Modifier.weight(1f)); auto(Modifier.weight(1f)) }
    }
}

@Composable private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
    }
}
private fun speed(bps: Long): String = when { bps < 1024 -> "$bps Б/с"; bps < 1048576 -> "${bps / 1024} КБ/с"; else -> String.format(Locale.ROOT, "%.1f МБ/с", bps / 1048576.0) }
private fun time(seconds: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
