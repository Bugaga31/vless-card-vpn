package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.worker.*
import java.util.Locale

@Composable
fun HomeScreen(repo: AppRepository, autoPilotEngine: AutoPilotEngine, vpnStats: VpnSessionStats,
    onToggleConnect: (VlessConfig?) -> Unit, onNavigateToServers: () -> Unit,
    onNavigateToAutopilot: () -> Unit, onNavigateToDiagnostic: () -> Unit,
    onNavigateToSettings: () -> Unit, onPanicTrigger: () -> Unit,
    preparingConnection: Boolean = false, onNavigateToCrashReports: () -> Unit = {},
    onAutoConnect: () -> Unit = { onToggleConnect(null) }, onDirectConnect: () -> Unit = {}) {
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val storageIssue by repo.storageIssueFlow.collectAsState()
    val selected = ConnectionSelection.current(configs, vpnStats.activeConfig,
        vpnStats.status in setOf(VpnStatus.CONNECTING, VpnStatus.CONNECTED, VpnStatus.STOPPING))
    HomeDashboard(vpnStats, selected, configs.size, preparingConnection,
        { onToggleConnect(selected) }, onAutoConnect, onNavigateToServers, onNavigateToSettings,
        onNavigateToDiagnostic, onNavigateToCrashReports, storageIssue = storageIssue, onDirect = onDirectConnect)
}

/** Real Compose UI: presentation cannot start a tunnel or infer health from a TCP ping. */
@Composable
fun HomeDashboard(stats: VpnSessionStats, selected: VlessConfig? = null, configCount: Int = 0,
    preparing: Boolean = false, onConnect: () -> Unit = {}, onAuto: () -> Unit = {},
    onServers: () -> Unit = {}, onSettings: () -> Unit = {}, onDiagnostics: () -> Unit = {},
    onReports: () -> Unit = {}, scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(), storageIssue: String? = null,
    onDirect: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val connected = stats.status == VpnStatus.CONNECTED
    val working = connected && stats.health.internet
    val limited = working && if (stats.direct) !stats.health.youtubeBulk else !stats.health.preferredServices
    val busy = stats.status == VpnStatus.CONNECTING || preparing
    val stopping = stats.status == VpnStatus.STOPPING
    val error = stats.status == VpnStatus.ERROR
    var showNetworkDiagnostics by remember { mutableStateOf(false) }
    val headline = when { busy && preparing -> "Разрешение VPN"; busy && stats.autoMode -> "Подбираем маршрут"; busy -> "Подключаемся"; stopping -> "Отключаемся"; limited -> "Частичный доступ"; working -> "Подключено"; storageIssue != null -> "Подключение недоступно"
        error && !stats.autoMode -> "Не удалось подключиться"; error -> "Маршрут не найден"
        connected && stats.health.checkedAt > 0 -> "Подключено · HTTPS не прошёл"; connected -> "Подключено по пингу"; else -> "Не подключено" }
    val description = when {
        busy -> stats.progressMessage.ifBlank { "Проверяем сервер и передачу данных." }
        stopping -> "Завершаем сеанс и освобождаем ресурсы."
        limited && stats.direct -> "Без сервера: сайты открываются, но YouTube не прошёл проверку загрузки."
        limited -> "HTTPS работает. Не все сервисы прошли проверку."
        working -> stats.profileLabel.ifBlank { "Связь через выбранный сервер подтверждена." }
        storageIssue != null -> "Сначала восстановите доступ к сохранённым данным."
        error -> stats.errorMessage ?: "Авто не нашло рабочий сервер. Можно повторить поиск или добавить подписку."
        connected -> stats.progressMessage.ifBlank { "Туннель запущен; HTTPS проверяется." }
        else -> if (selected != null) "Подключите выбранный сервер или запустите Авто." else "Добавьте подписку или запустите поиск Авто."
    }
    val stateColor = when { limited -> c.primary; working -> c.tertiary; error -> c.error; busy -> c.primary; else -> c.onSurfaceVariant }
    CompositionLocalProvider(LocalContentColor provides c.onBackground) {
        Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding()
            .verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = c.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Shield, null, tint = c.primary, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("VLESS Card", Modifier.weight(1f).semantics { heading() }, fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = c.onBackground)
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Settings, "Настройки", tint = c.onSurfaceVariant)
                }
            }
            if (storageIssue != null) StorageProtectionPanel(storageIssue)
            Surface(shape = RoundedCornerShape(20.dp), color = c.surface,
                border = BorderStroke(1.dp, c.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = when { limited -> c.primaryContainer; working -> c.tertiaryContainer; error -> c.errorContainer; else -> c.primaryContainer }) {
                            Icon(if (limited) Icons.Default.Info else if (working) Icons.Default.Check else if (error) Icons.Default.Info else Icons.Default.PowerSettingsNew,
                                null, tint = stateColor, modifier = Modifier.padding(12.dp).size(24.dp))
                        }
                        Text(headline, Modifier.weight(1f).semantics { heading() }, fontSize = 26.sp, lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold, color = c.onSurface)
                    }
                    Text(description, fontSize = 16.sp, lineHeight = 24.sp, color = c.onSurfaceVariant)
                    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = c.primary, trackColor = c.surfaceVariant)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (busy || connected || stopping) {
                            Button(onClick = onConnect, enabled = !stopping,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                                Text(if (busy) "Отменить" else "Отключить", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (connected) TextButton(onClick = onAuto, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Авто", fontSize = 16.sp)
                            }
                        } else {
                            if (selected != null) ManualAutoActions(onConnect, onAuto, storageIssue == null)
                            else Button(onClick = onAuto, enabled = storageIssue == null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Авто", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(onClick = onDirect, enabled = storageIssue == null, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Без сервера (как ByeByeDPI)", fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
            // Connection failures are network events, not necessarily application crashes.
            // Opening this panel never runs a probe, sends a report or restarts a tunnel.
            if (storageIssue == null && (error || limited)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showNetworkDiagnostics = !showNetworkDiagnostics },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.Info, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (showNetworkDiagnostics) "Скрыть диагностику" else "Диагностика подключения", fontSize = 16.sp)
                    }
                    if (showNetworkDiagnostics) NetworkDiagnosticsPanel()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Маршрут")
                Surface(onClick = onServers, enabled = storageIssue == null, shape = RoundedCornerShape(16.dp), color = c.surface,
                    border = BorderStroke(1.dp, c.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, tint = c.primary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (storageIssue != null) "Серверы недоступны" else selected?.name?.ifBlank { "Сервер из подписки" } ?: "Добавить подписку", fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (storageIssue != null) "Сохранённые данные не удалены" else if (selected == null) "Авто также использует публичные серверы" else
                                "${selected.protocolType.uppercase(Locale.ROOT)} · ${selected.security.uppercase(Locale.ROOT)} · $configCount серверов",
                                fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ChevronRight, null, tint = c.onSurfaceVariant)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Проверка связи", Modifier.weight(1f))
                    TextButton(onClick = onDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("Подробнее", fontSize = 14.sp) }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = c.surface, border = BorderStroke(1.dp, c.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        val checked = stats.health.checkedAt > 0
                        val rows = listOf("Контрольный HTTPS" to stats.health.probes.firstOrNull { it.label == "Cloudflare" },
                            "YouTube · HTTPS" to stats.health.probes.firstOrNull { it.label == "YouTube · HTTPS" },
                            "Telegram · веб" to stats.health.probes.firstOrNull { it.label == "Telegram · веб" })
                        rows.forEachIndexed { index, (label, probe) ->
                            CheckRow(label, checked, probe)
                            if (index < rows.lastIndex) HorizontalDivider(color = c.outlineVariant)
                        }
                    }
                }
                Text("Веб-проверка не гарантирует видео и звонки или работу Telegram через MTProto.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurfaceVariant)
            }
            if (working) Surface(shape = RoundedCornerShape(16.dp), color = c.surface, border = BorderStroke(1.dp, c.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("В сети", duration(stats.durationSeconds), Modifier.weight(1f))
                    Metric("Отклик HTTPS", "${stats.health.latencyMs} мс", Modifier.weight(1f))
                }
            }
            if (error) OutlinedButton(onClick = onReports, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)) { Text("Отчёты об ошибках", fontSize = 16.sp) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
/** Side-by-side at normal text size; stacked on narrow layouts / enlarged accessibility text. */
@Composable private fun ManualAutoActions(onConnect: () -> Unit, onAuto: () -> Unit, enabled: Boolean) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 280.dp && scale <= 1.25f) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ManualConnectAction(onConnect, enabled, Modifier.weight(1.5f))
            AutoConnectAction(onAuto, enabled, Modifier.weight(1f))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ManualConnectAction(onConnect, enabled, Modifier.fillMaxWidth())
            AutoConnectAction(onAuto, enabled, Modifier.fillMaxWidth())
        }
    }
}
@Composable private fun ManualConnectAction(onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text("Подключить", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun AutoConnectAction(onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text("Авто", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground)
}
@Composable private fun CheckRow(label: String, checked: Boolean, probe: TunnelProbe?) {
    val c = MaterialTheme.colorScheme
    val passed = probe?.passed == true
    val measured = checked && probe != null
    val text = TunnelProbePresentation.status(probe, checked)
    val color = if (!measured) c.onSurfaceVariant else if (passed) c.tertiary else c.error
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurface)
        Spacer(Modifier.width(12.dp))
        Icon(if (!measured) Icons.Default.Remove else if (passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp)); Text(text, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
private fun duration(s: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
