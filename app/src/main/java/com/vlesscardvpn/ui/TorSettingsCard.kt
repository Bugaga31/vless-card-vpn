package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.Tunnel
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.tor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tor-only redesign; Ocean theme and the rest of Settings are unchanged. */
@Composable
fun TorSettingsCard(settings: Settings, set: ((Settings) -> Settings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(TorBridgeUpdates.enabled(ctx)) }
    val updateStatus by TorBridgeUpdates.status.collectAsState()
    val vpn by Tunnel.status.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var circuit by remember(settings.torBridge, settings.torBridgeLines, settings.torRouting, settings.torAll, vpn.state, vpn.since) { mutableStateOf<Boolean?>(null) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(settings.torBridge) { auto = TorBridgeUpdates.enabled(ctx) }
    val parsed = remember(settings.torBridgeLines, settings.torBridge) {
        runCatching { TorBridgeCatalog.parse(settings.torBridgeLines).forTransport(settings.torBridge).size }.getOrDefault(0)
    }
    fun refresh() {
        loading = true
        scope.launch { try { TorBridgeUpdates.refresh(ctx, manual = true) } finally { loading = false } }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("TOR", Modifier.padding(12.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("Tor-подключение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Встроен в приложение · без Termux", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (circuit) { true -> "Tor-цепочка через VLESS создана"; false -> "Готовая Tor-цепочка не обнаружена"; null -> "Tor-цепочка ещё не проверена" }, fontWeight = FontWeight.SemiBold)
                    Text("Статус локальной цепочки не подтверждает Telegram или YouTube. Их проверка выполняется отдельно.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        checking = true
                        val observed = Store.state.value.settings
                        val observedVpn = Tunnel.status.value
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val cookie = TorCard.readCookieHex(ctx) ?: return@withContext false
                                    val control = TorControl("127.0.0.1", TorCard.DEFAULT_CONTROL_PORT)
                                    try { control.connect(cookie); control.circuitEstablished() }
                                    catch (_: Exception) { false } finally { control.close() }
                                }
                                val current = Store.state.value.settings
                                if (current.torBridge == observed.torBridge && current.torBridgeLines == observed.torBridgeLines &&
                                    current.torRouting == observed.torRouting && current.torAll == observed.torAll &&
                                    Tunnel.status.value == observedVpn) circuit = result
                            } finally { checking = false }
                        }
                    }, enabled = !checking && vpn.state == Tunnel.State.CONNECTED, modifier = Modifier.fillMaxWidth()) {
                        Text(if (checking) "Проверяю локальный Tor…" else "Проверить Tor-цепочку")
                    }
                }
            }
            Text("В режиме Авто сначала выбирается проверенный VLESS/Hysteria-сервер. Tor, если включён ниже, работает только поверх этого сервера; при отсутствии маршрута VPN не переключается на прямое соединение.", style = MaterialTheme.typography.bodyMedium)
            TorOption("Автоподбор сетевых маршрутов", "Сравнивать ограниченный набор проверенных VLESS-маршрутов и их маскировок. Авто не использует прямое соединение и не проверяет Tor отдельно от VLESS.", settings.autoChainSearch) { v -> set { it.copy(autoChainSearch = v) } }
            Text("Приоритет подбора",fontWeight=FontWeight.Medium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("balanced" to "Баланс","download" to "Скорость","interactive" to "Отклик").forEach {(id,title) ->
                    FilterChip(selected=settings.autoChainPurpose==id,onClick={set {it.copy(autoChainPurpose=id)}},label={Text(title)})
                }
            }
            Text("Работает в VPN-режиме Авто без отдельной сервисной маршрутизации и WARP-цепочки. Измеряется лучший из ограниченного набора, не каждый сервер интернета.",style=MaterialTheme.typography.bodySmall)
            TorOption("Рекомендуемые профили Wi-Fi / мобильная сеть", "При подключении и смене сети: Wi-Fi — порог HTTPS 2500 мс, интервал ротации 600 с; мобильная — 5000 мс и 900 с. Это начальные настройки, не измеренная скорость. Мосты и выбор VLESS-сервера не меняются.", settings.torNetworkRecommendations) { v -> set { it.copy(torNetworkRecommendations = v) } }
            if (settings.torNetworkRecommendations) Text("Ручные интервал и порог ниже сохранены, но временно заменяются профилем Wi-Fi/мобильной сети. Выключите рекомендации, чтобы применять свои значения. На других типах сети используются ручные настройки.", style = MaterialTheme.typography.bodySmall)
            if (settings.torAll) {
                TorOption("Ротация Tor-цепочек", "Запрашивать новую Tor-цепочку для новых соединений через VLESS. Активные соединения не обещано сохранить при смене выхода.", settings.torRotation) { v -> set { it.copy(torRotation = v) } }
            }
            if (settings.torAll) {
                var seconds by remember(settings.torRotationSeconds) { mutableStateOf(settings.torRotationSeconds.toString()) }
                var latency by remember(settings.torMaxLatencyMs) { mutableStateOf(settings.torMaxLatencyMs.toString()) }
                val validSeconds = seconds.toIntOrNull()?.takeIf { it in 30..86400 }
                val validLatency = latency.toIntOrNull()?.takeIf { it > 0 }
                OutlinedTextField(value = seconds, onValueChange = { if (it.length <= 5) seconds = it }, singleLine = true,
                    label = { Text("Запрашивать новую цепочку каждые N секунд") },
                    supportingText = { Text("30–86400 секунд. В режиме энергосбережения ротация может откладываться.") },
                    isError = validSeconds == null, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                TorOption("Предпочитать быстрые выходы", "Выбирать выход по свежей задержке HTTPS через Tor; это не обещание скорости скачивания.", settings.torFastOnly) { v -> set { it.copy(torFastOnly = v) } }
                OutlinedTextField(value = latency, onValueChange = { if (it.length <= 10) latency = it }, singleLine = true,
                    label = { Text("Порог задержки HTTPS, мс") }, supportingText = { Text("Положительное целое число. Слишком низкий порог может исключить все замеры.") },
                    isError = validLatency == null, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedButton(onClick = { if (validSeconds != null && validLatency != null) set { it.copy(torRotationSeconds = validSeconds, torMaxLatencyMs = validLatency) } },
                    enabled = validSeconds != null && validLatency != null, modifier = Modifier.fillMaxWidth()) { Text("Сохранить интервал и порог") }
                Text("Параметры ротации и транспорта применяются при следующем подключении. Tor работает поверх VLESS; при отсутствии сервера маршрут не переключается напрямую.", style = MaterialTheme.typography.bodyMedium)
            }
            TorOption("Весь TCP-трафик через Tor", "Tor работает вторым шагом после выбранного VLESS-сервера; UDP, включая звонки, остаётся на VLESS-маршруте. Если VLESS недоступен, VPN не уходит напрямую.", settings.torAll) { v -> set { it.copy(torAll = v) } }
            Text("Транспорт и мосты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            listOf("obfs4" to "obfs4 · маскировка Tor", "webtunnel" to "WebTunnel · HTTPS-транспорт", "none" to "Без мостов · через VLESS").forEach { (id, label) ->
                Surface(onClick = { circuit = null; set { it.copy(torBridge = id) } }, shape = RoundedCornerShape(12.dp),
                    color = if (settings.torBridge == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.torBridge == id, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (settings.torBridge !in TorCard.EMBEDDED_BRIDGES) Text("Этот транспорт не встроен. Выберите obfs4, WebTunnel или явно режим без мостов.", color = MaterialTheme.colorScheme.error)
            if (settings.torBridge != "none") {
                Text("$parsed корректных мостов ${settings.torBridge} · максимум 12", fontWeight = FontWeight.Medium)
                TorOption("Автообновление мостов", "Список igareck TOP100: раз в 6 часов при работе Tor, повтор ошибки не чаще 15 минут. Источник увидит запрос; старые мосты сохранятся при сбое.", auto) { value ->
                    auto = value; TorBridgeUpdates.setEnabled(ctx, value)
                    if (value) scope.launch { TorBridgeUpdates.refresh(ctx) }
                }
                Text("Источник: igareck TOP100 · raw.githack.com", style = MaterialTheme.typography.bodyMedium)
                val updated = remember(updateStatus, settings.torBridge) { TorBridgeUpdates.lastUpdated(ctx, settings.torBridge) }
                if (updated > 0) Text("Обновлено: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(updated)), style = MaterialTheme.typography.bodyMedium)
                Text(updateStatus, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (loading) "Обновляю список…" else "Обновить мосты сейчас")
                }
                Text("Новый список применяется при следующем подключении — текущая цепочка не обрывается. TOP100 не гарантирует доступность или скорость.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Скрыть ручной ввод" else "Вставить свои мосты") }
                if (editing) {
                    OutlinedTextField(value = settings.torBridgeLines, onValueChange = { v -> set { it.copy(torBridgeLines = v) } },
                        label = { Text("По одному мосту на строку") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://bridges.torproject.org/options"))) } }) { Text("Получить мосты Tor Project") }
                }
            }

        }
    }
}

@Composable
private fun TorOption(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
