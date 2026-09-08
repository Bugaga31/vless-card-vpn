package com.vlesscardvpn.xraytest

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Champagne = Color(0xFFE1C78F)
private val Muted = Color(0xFFADBDB3)
private val Ink = Color(0xFF0D1411)

@Composable
fun LoungeScreen(entries: List<CardProfile>, working: Boolean, loaded: Boolean, permissionPending: Boolean, message: String,
    onReload: () -> Unit, onImport: (String) -> Unit, onFetch: (Boolean) -> Unit, onCancelFetch: () -> Unit,
    onFavorite: (String) -> Unit, onDelete: (String) -> Unit,
    onConnect: (Boolean, Boolean, String?, Boolean) -> Unit, onStop: () -> Unit, onTurbo: () -> Unit, report: () -> String) {
    val session by TestState.session.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var auto by rememberSaveable { mutableStateOf(true) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var probeConsent by rememberSaveable { mutableStateOf(true) }
    var sourceConsent by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<CardProfile?>(null) }
    var reportText by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val vpnBusy = session.busy || session.active
    val locked = working || permissionPending || vpnBusy || !loaded
    val chosen = entries.firstOrNull { it.key == selected } ?: entries.firstOrNull()
    val candidates = ProfileCatalog.candidates(entries, auto, favoritesOnly, chosen?.key)
    val activeName = RunningLabels.names.getOrNull(session.node)
    MaterialTheme(colorScheme = darkColorScheme(primary = Champagne, onPrimary = Ink, background = Ink,
        surface = Color(0xFF17221B), onSurface = Color(0xFFE8EEE9), secondary = Color(0xFF8BC5A3))) {
        Scaffold(modifier = Modifier.safeDrawingPadding(), containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF121C16)) {
                    val labels = listOf("Главная", "Серверы", "Источники", "Авто", "Отчёт")
                    val icons = listOf("⌂", "≡", "↓", "A", "⋯")
                    labels.forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(icons[i], fontSize = 23.sp) }, label = { Text(label, fontSize = 10.sp) }) }
                }
            }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PRIVATE LOUNGE", color = Champagne, letterSpacing = 2.sp, fontSize = 12.sp)
                        Text("XRAY · TEST", color = Muted, fontSize = 11.sp)
                    }
                }
                item { Text(listOf("Твоя приватная линия", "Коллекция серверов", "Новые подключения", "Умное подключение", "Диагностика связи")[tab], fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp) }
                if (message.isNotBlank()) item { Text(message, color = Muted, fontSize = 13.sp) }
                if (working || permissionPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loaded && !working) item { OutlinedButton(onClick = onReload) { Text("Повторить загрузку списка") } }
                when (tab) {
                    0 -> {
                        item {
                            Card(shape = RoundedCornerShape(30.dp), border = BorderStroke(1.dp, Color(0xFF46523C))) {
                                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF294433), Color(0xFF152019)))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text(if (session.active) "ТУННЕЛЬ ЗАПУЩЕН" else if (session.busy) "УСТАНАВЛИВАЕМ СВЯЗЬ" else "ГОТОВО К ПОДКЛЮЧЕНИЮ", color = Champagne, fontSize = 11.sp, letterSpacing = 1.sp)
                                    Text(if (session.active) "Оставайся на связи." else "Твой маршрут.\nТвои правила.", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp)
                                    Text(activeName ?: chosen?.name ?: "Добавь первый сервер — без случайных демо-узлов", color = Color(0xFFD5E0D8))
                                    Text(session.message, fontSize = 13.sp)
                                    if (vpnBusy) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Отключить / отменить") }
                                    else {
                                        Button(onClick = onTurbo, enabled = !locked && probeConsent, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) { Text("⚡ ТУРБО — одна кнопка", fontSize = 16.sp) }
                                        OutlinedButton(onClick = { onConnect(auto, favoritesOnly, chosen?.key, probeConsent) }, enabled = !locked && probeConsent && (candidates.isNotEmpty() || auto), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(18.dp)) { Text(if (auto) "Подключить автоматически" else "Подключить сервер") }
                                    }
                                }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatCard("КОЛЛЕКЦИЯ", "${entries.size} / ${ProfileCatalog.LIMIT}", Modifier.weight(1f))
                                StatCard("РЕЖИМ", if (auto) "Авто" else "Вручную", Modifier.weight(1f))
                            }
                        }
                        item { ToggleRow("Авто-подключение", "Первый прошедший проверки сервер, затем поиск замены при сбоях", auto, !locked) { auto = it } }
                        item { Text("⚡ ТУРБО: загружает серверы при пустом списке, параллельно меряет TCP-задержку всех узлов и подключает самый быстрый. Остальные отсортированы по скорости как запасные.", color = Muted, fontSize = 12.sp) }
                        item { ToggleRow("Разрешить проверки", "Три HTTPS-запроса на Telegram-сайт и YouTube через v2ray, затем периодические проверки. Это не тест звонков и видео.", probeConsent, !locked) { probeConsent = it } }
                        item { OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Открыть коллекцию серверов") } }
                        item { Text("Серверы сохраняются локально в зашифрованном хранилище Android Keystore. Тестовая версия не является kill switch.", color = Muted, fontSize = 12.sp) }
                    }
                    1 -> {
                        item { Button(onClick = { importOpen = true }, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text("Вставить ссылки / подписку") } }
                        if (entries.isEmpty()) item { Text("Пока пусто. Импортируй свои VLESS-ссылки или открой «Источники». Ничего не добавляется само.", color = Muted) }
                        items(entries, key = { it.key }) { entry ->
                            Card(shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, if (chosen?.key == entry.key) Champagne else Color(0xFF2E3D32))) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(entry.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                    Text(entry.origin, color = Muted, fontSize = 12.sp)
                                    Text("VLESS · ${entry.node().params["security"]?.uppercase()} · ${entry.node().params["type"] ?: "tcp"}", color = Muted, fontSize = 12.sp)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { selected = entry.key; auto = false; tab = 0 }, enabled = !locked) { Text("Выбрать") }
                                        TextButton(onClick = { onFavorite(entry.key) }, enabled = !locked) { Text(if (entry.favorite) "★" else "☆", fontSize = 24.sp) }
                                        TextButton(onClick = { delete = entry }, enabled = !locked) { Text("Удалить") }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        item { Text("Загрузка из публичных списков. Автопарсер распознаёт обычные и Base64-списки, пропускает неподдерживаемые ссылки и удаляет дубликаты. До ${ProfileCatalog.LIMIT} сохранённых серверов.", color = Muted) }
                        item { Card(shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Источники из прежней версии", color = Champagne)
                            PublicSources.sources.forEach { Text(it.first, fontSize = 14.sp) }
                            Text("Публичный не означает надёжный. Загруженные узлы ещё не проверены.", color = Muted, fontSize = 12.sp)
                        } } }
                        item { ToggleRow("Разрешить загрузку", "GitHub получит запросы и твой внешний IP: это приложение исключено из VPN. При подключении оператор публичного VPN сможет видеть метаданные трафика. Не используй такие узлы для чувствительных действий.", sourceConsent, !locked) { sourceConsent = it } }
                        item { Button(onClick = { onFetch(sourceConsent) }, enabled = !locked && sourceConsent, modifier = Modifier.fillMaxWidth()) { Text("Загрузить и добавить") } }
                        if (working) item { OutlinedButton(onClick = onCancelFetch, modifier = Modifier.fillMaxWidth()) { Text("Отменить загрузку") } }
                        item { Text("Не загружаем новые адреса из вложенных списков и не отключаем проверку TLS. Для проверки самих узлов включи авто-режим после импорта.", color = Muted, fontSize = 12.sp) }
                    }
                    3 -> {
                        item { ToggleRow("Автоматический режим", "Подключается с нуля и перебирает доступные кандидаты. Работает в VPN-сервисе, а не только на открытом экране.", auto, !locked) { auto = it } }
                        item { ToggleRow("Только избранные", "В авто-подбор попадут лишь серверы со звездой. Пустое избранное не заменяется всеми серверами.", favoritesOnly, !locked) { favoritesOnly = it } }
                        item { ToggleRow("Разрешить HTTPS-проверки", "Проверяются Telegram-сайт и YouTube. Автоматическая смена сервера может прервать текущие соединения.", probeConsent, !locked) { probeConsent = it } }
                        item { StatCard("КАНДИДАТОВ ДЛЯ ЗАПУСКА", "${candidates.size}", Modifier.fillMaxWidth()) }
                        item { Text("Как работает\n\n1. Запускает выбранного кандидата.\n2. Проверяет оба HTTPS-адреса через v2ray.\n3. Оставляет первый прошедший проверки сервер.\n4. После трёх неудачных раундов и минимум 60 секунд ищет замену.\n\nЭто не рейтинг самого быстрого сервера. Ошибка запуска ядра останавливает попытку. Изменить режим и список можно после отключения.", color = Muted, lineHeight = 23.sp) }
                        item { if (vpnBusy) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Отключить авто / VPN") }
                            else Button(onClick = { onConnect(auto, favoritesOnly, chosen?.key, probeConsent) }, enabled = !locked && probeConsent && (candidates.isNotEmpty() || auto), modifier = Modifier.fillMaxWidth()) { Text("Запустить подключение") } }
                    }
                    4 -> {
                        item { Text(session.message, fontSize = 18.sp) }
                        item { Text("Отчёт содержит этапы подключения, результаты HTTPS-проверок с медианой задержки и сведения о вылете. Успешный SOCKS-тест сам по себе не доказывает прохождение трафика других приложений через TUN.", color = Muted) }
                        item { Button(onClick = { reportText = report() }, modifier = Modifier.fillMaxWidth()) { Text("Открыть отчёт") } }
                        item { Text("После вылета открой приложение снова и скопируй отчёт. Полные ссылки и текст ошибок ядра в него не добавляются. Всё равно проверь содержимое перед отправкой.", color = Muted) }
                        item { Text("${BuildConfig.VERSION_NAME}\nОтдельное приложение на ядре v2ray (v2fly). Старое приложение и его данные не изменяются. При остановке VPN Android может восстановить прямое соединение.", color = Muted, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (importOpen) AlertDialog(onDismissRequest = { importOpen = false; input = "" }, title = { Text("Импорт серверов") }, text = {
            OutlinedTextField(input, { if (it.length <= 2097152) input = it }, label = { Text("VLESS-ссылки или Base64-список") }, visualTransformation = PasswordVisualTransformation(), minLines = 3, maxLines = 6)
        }, confirmButton = { TextButton(onClick = { onImport(input); input = ""; importOpen = false }, enabled = !locked && input.isNotBlank()) { Text("Добавить") } }, dismissButton = { TextButton(onClick = { importOpen = false; input = "" }) { Text("Отмена") } })
        delete?.let { entry -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Удалить сервер?") }, text = { Text(entry.name) }, confirmButton = { TextButton(onClick = { onDelete(entry.key); delete = null }, enabled = !locked) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Отмена") } }) }
        reportText?.let { text -> AlertDialog(onDismissRequest = { reportText = null }, title = { Text("Отчёт") }, text = { Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)); reportText = null }) { Text("Скопировать") } }, dismissButton = { TextButton(onClick = { reportText = null }) { Text("Закрыть") } }) }
    }
}
@Composable private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(modifier, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(value, color = Champagne, fontSize = 22.sp, fontFamily = FontFamily.Serif)
    } }
}
@Composable private fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
