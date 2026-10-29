package com.vlesscardvpn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

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
    var advanced by rememberSaveable { mutableStateOf(false) }
    val torEnabled = settings.torAll || settings.torRouting || (settings.mode == com.vlesscardvpn.model.Mode.AUTO && settings.torAuto)

    LaunchedEffect(settings.torBridge) { auto = TorBridgeUpdates.enabled(ctx) }
    val parsed = remember(settings.torBridgeLines, settings.torBridge) {
        runCatching { TorBridgeCatalog.parse(settings.torBridgeLines).forTransport(settings.torBridge).size }.getOrDefault(0)
    }
    LaunchedEffect(torEnabled, vpn.state, vpn.since, settings.torBridge, settings.torBridgeLines) {
        if (!torEnabled || vpn.state != Tunnel.State.CONNECTED) {
            circuit = null
            return@LaunchedEffect
        }
        while (true) {
            circuit = torCircuitReady(ctx)
            kotlinx.coroutines.delay(if (circuit == true) 15_000L else 2_500L)
        }
    }
    fun refresh() {
        loading = true
        scope.launch { try { TorBridgeUpdates.refresh(ctx, manual = true) } finally { loading = false } }
    }
    Surface(modifier = Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("TOR", Modifier.padding(horizontal = 14.dp, vertical = 12.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Tor-подключение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(if (settings.torAll) "Весь TCP через Tor · UDP через VLESS" else if (settings.torRouting) "Tor для выбранных приложений" else if (settings.torAuto) "Tor для авто-восстановления" else "Tor выключен",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(50), color = if (torEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                    Text(if (!torEnabled) "ВЫКЛ" else if (circuit == true) "ГОТОВ" else "ВКЛ",
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = if (torEnabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { set { TorConnectionPolicy.automatic(it) } }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)) {
                Text(if (settings.mode == com.vlesscardvpn.model.Mode.AUTO && settings.torAll && settings.torBridge == "none" && settings.torNetworkRecommendations && settings.torRotation && settings.torFastOnly && !settings.torDirect) "Автонастройка включена" else "Настроить Tor автоматически")
            }
            Text("Профиль применится к настройкам; подключение запускается главной кнопкой. Tor работает поверх VPN без прямого обхода и может строить первую цепочку дольше пяти секунд.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when {
                        !torEnabled -> "Tor пока не включён"
                        vpn.state != Tunnel.State.CONNECTED -> "Сначала подключите VPN"
                        circuit == true -> "Tor-цепочка готова"
                        circuit == false -> "Tor-цепочка пока не подтвердилась"
                        else -> "VPN подключён · ожидает проверки Tor"
                    }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("Это проверяет локальную Tor-цепочку, но не скорость и не доступность отдельных приложений.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = {
                        checking = true
                        val observed = Store.state.value.settings
                        val observedVpn = Tunnel.status.value
                        scope.launch {
                            try {
                                val result = torCircuitReady(ctx)
                                val current = Store.state.value.settings
                                if (current.torBridge == observed.torBridge && current.torBridgeLines == observed.torBridgeLines &&
                                    current.torRouting == observed.torRouting && current.torAll == observed.torAll &&
                                    Tunnel.status.value == observedVpn) circuit = result
                            } finally { checking = false }
                        }
                    }, enabled = !checking && torEnabled && vpn.state == Tunnel.State.CONNECTED, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)) {
                        Text(if (checking) "Проверяю цепочку…" else "Проверить Tor")
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Скрыть дополнительные настройки" else "Маршруты, выходы и мосты")
            }
            if (advanced) {
                Text("В режиме Авто Tor работает поверх выбранного VLESS-маршрута. При отсутствии маршрута прямого обхода нет.", style = MaterialTheme.typography.bodySmall)
                val autoChainAvailable = settings.mode == com.vlesscardvpn.model.Mode.AUTO && !settings.torAll && !settings.torRouting &&
                    settings.services.isEmpty() && !settings.warpChain && !settings.proxyOnly
                if (autoChainAvailable) {
                    TorOption("Автоподбор сетевых цепочек", "Сравнивать ограниченный набор проверенных маршрутов. Прямое соединение не используется.", settings.autoChainSearch) { v -> set { it.copy(autoChainSearch = v) } }
                    Text("Приоритет подбора", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("balanced" to "Баланс", "download" to "Скорость", "interactive" to "Отклик").forEach { (id, title) ->
                            FilterChip(selected = settings.autoChainPurpose == id, onClick = { set { it.copy(autoChainPurpose = id) } }, label = { Text(title) })
                        }
                    }
                    Text("Измеряется небольшой набор маршрутов, не весь интернет.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("В текущем режиме автоподбор сетевых цепочек не используется. Для Tor включены отдельный VLESS-маршрут и измеряемая ротация выходов.", style = MaterialTheme.typography.bodySmall)
                }
            TorCountryPreferences(settings,set)
            TorOption("Рекомендуемые профили Wi-Fi / мобильная сеть", "При подключении и смене сети: Wi-Fi — допуск задержки ${TorNetworkRecommendations.WIFI_LATENCY_MS} мс и ротация 600 с; мобильная — ${TorNetworkRecommendations.MOBILE_LATENCY_MS} мс и 900 с. Допуск нужен, чтобы Tor не отбрасывал медленные, но рабочие выходы; это не обещание скорости.", settings.torNetworkRecommendations) { v -> set { it.copy(torNetworkRecommendations = v) } }
            if (settings.torNetworkRecommendations) Text("Ручные интервал и порог ниже сохранены, но временно заменяются профилем Wi-Fi/мобильной сети. Выключите рекомендации, чтобы применять свои значения. На других типах сети используются ручные настройки.", style = MaterialTheme.typography.bodySmall)
            if (settings.torAll) {
                TorOption("Ротация Tor-цепочек", "Запрашивать новую Tor-цепочку для новых соединений через VLESS. Активные соединения не обещано сохранить при смене выхода.", settings.torRotation) { v -> set { it.copy(torRotation = v) } }
            }
            if (settings.torAll) {
                var seconds by remember(settings.torRotationSeconds) { mutableStateOf(settings.torRotationSeconds.toString()) }
                var latency by remember(settings.torMaxLatencyMs) { mutableStateOf(settings.torMaxLatencyMs.toString()) }
                val validSeconds = seconds.toIntOrNull()?.takeIf { it in 5..86400 }
                val validLatency = latency.toIntOrNull()?.takeIf { it > 0 }
                OutlinedTextField(value = seconds, onValueChange = { if (it.length <= 5) seconds = it }, singleLine = true,
                    label = { Text("Запрашивать новую цепочку каждые N секунд") },
                    supportingText = { Text("5–86400 секунд — интервал запросов. Tor может ограничить частоту; текущие соединения сохраняют старые цепочки.") },
                    isError = validSeconds == null, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                TorOption("Предпочитать быстрые выходы", "Выбирать выход по свежей задержке HTTPS через Tor; это не обещание скорости скачивания.", settings.torFastOnly) { v -> set { it.copy(torFastOnly = v) } }
                OutlinedTextField(value = latency, onValueChange = { if (it.length <= 10) latency = it }, singleLine = true,
                    label = { Text("Порог задержки HTTPS, мс") }, supportingText = { Text("Положительное целое число. Слишком низкий порог может исключить все замеры.") },
                    isError = validLatency == null, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedButton(onClick = { if (validSeconds != null && validLatency != null) set { it.copy(torRotationSeconds = validSeconds, torMaxLatencyMs = validLatency, torNetworkRecommendations = false) } },
                    enabled = validSeconds != null && validLatency != null, modifier = Modifier.fillMaxWidth()) { Text("Сохранить интервал и порог") }
                Text("Параметры ротации и транспорта применяются при следующем подключении. Tor работает поверх VLESS; при отсутствии сервера маршрут не переключается напрямую.", style = MaterialTheme.typography.bodyMedium)
            }
            TorOption("Весь TCP-трафик через Tor", "Tor работает вторым шагом после выбранного VLESS-сервера; UDP, включая звонки, остаётся на VLESS-маршруте. Если VLESS недоступен, VPN не уходит напрямую.", settings.torAll) { v -> set { if (v) TorConnectionPolicy.selectTor(it) else it.copy(torAll = false) } }
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
}

private suspend fun torCircuitReady(ctx: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    TorCard.controlEndpoints(ctx).any { endpoint ->
        val control = TorControl("127.0.0.1", endpoint.port, 600)
        try { control.connect(endpoint.cookieHex); control.circuitEstablished() }
        catch (_: Exception) { false } finally { control.close() }
    }
}

@Composable
private fun TorOption(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun TorCountryPreferences(settings:Settings,set:((Settings)->Settings)->Unit) {
 var text by remember(settings.torExitCountries) {mutableStateOf(settings.torExitCountries)}
 val countries=runCatching {TorCountryPolicy.parse(text)}.getOrNull()
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Text("Страна выхода Tor",style=MaterialTheme.typography.titleMedium)
  OutlinedTextField(value=text,onValueChange={if(it.length<=200) text=it},modifier=Modifier.fillMaxWidth(),
   label={Text("ISO-коды: US, DE, NL")},isError=countries==null,
   supportingText={Text("Пусто — любая страна. Несколько кодов — допустимые выходы, не порядок узлов.")})
  Button(onClick={countries?.let {cc->set {it.copy(torExitCountries=cc.joinToString(","))}}},
   enabled=countries!=null,modifier=Modifier.fillMaxWidth()) {Text("Сохранить страны")}
  Text("После переподключения встроенного Tor. Без доступного выхода выбранной страны Tor может не подключиться. VPN-сервер → Tor: первый шаг выбирайте в серверах. Дополнительные VPN-шаги пока не поддерживаются.",style=MaterialTheme.typography.bodyMedium)
 }
}
