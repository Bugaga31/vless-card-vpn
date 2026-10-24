package com.vlesscardvpn.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Apps
import com.vlesscardvpn.core.ByeDpiArgs
import com.vlesscardvpn.core.DpiStrategies
import com.vlesscardvpn.core.Net
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.Tester
import com.vlesscardvpn.core.XrayCore
import com.vlesscardvpn.model.Balance
import com.vlesscardvpn.model.Settings

@Composable
fun SettingsScreen() {
    val s by Store.settings.collectAsState()
    val busy by Actions.running.collectAsState()
    val ctx = LocalContext.current
    fun set(f: (Settings) -> Settings) = Store.update { it.copy(settings = f(it.settings)) }
    var appsDialog by remember { mutableStateOf(false) }
    var wipeDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Настройки", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
        val storageError by Store.storageError.collectAsState()
        if (storageError.isNotEmpty()) Text(storageError, color = Bad, fontSize = 13.sp)
        Group("Белые списки оператора") {
            val wl by com.vlesscardvpn.core.Whitelist.last.collectAsState()
            val sc = rememberCoroutineScope(); var checking by remember { mutableStateOf(false) }
            Hint(wl?.let { com.vlesscardvpn.core.Whitelist.report(it) } ?: "Проверяет напрямую (мимо VPN), открываются ли только разрешённые сайты — так понятно, какие способы обхода вообще могут работать в этой сети. На мобильном автопилот проверяет сам при смене сети.")
            Button(onClick = { checking = true; sc.launch { com.vlesscardvpn.core.Whitelist.check(); checking = false } }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                Text(if (checking) "Проверяю…" else "Проверить сеть")
            }
        }
        Group("Защита данных") {
            Hint(if (com.vlesscardvpn.BuildConfig.DEBUG) "Отладочная сборка: state.json не зашифрован для тестов. Для повседневного использования установите подписанный релиз."
                else "Локальные серверы, подписки и настройки шифруются AES-256-GCM; ключ хранится в Android Keystore. Токен личного GitHub-облака тоже зашифрован. При утрате ключа потребуется ваша переносимая резервная копия.")
        }
        Group("Общее облако") {
            val rctx = androidx.compose.ui.platform.LocalContext.current
            var ron by remember { mutableStateOf(com.vlesscardvpn.core.Report.enabled(rctx)) }
            Toggle("Публичные подсказки Xray", "Проверенные облаком конфигурации, свежие замеры скорости и ограниченные рецепты обхода. Облачные кандидаты ранжируются во всём каталоге и получают места в ограниченных волнах проверки, но доступность и скорость подтверждает телефон. Это не личный GitHub-backup и не гарантия скорости у вашего оператора.", ron) {
                ron = it; com.vlesscardvpn.core.Report.setEnabled(rctx, it)
            }
            val publicStatus by com.vlesscardvpn.core.CloudSpeed.status.collectAsState()
            Text(publicStatus, fontSize = 12.sp)
            OutlinedButton(onClick = { com.vlesscardvpn.core.Actions.refreshPublicCloud() }, enabled = ron) { Text("Обновить публичные подсказки") }
            OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Bugaga31/vless-card-vpn/blob/servers/devices.md"))) } }) { Text("Сводка устройств в облаке") }
            if (!com.vlesscardvpn.core.Report.available()) Text("В этой сборке отправка отчётов отключена — только приём общего облака.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.vlesscardvpn.core.Report.lastText(rctx).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp) }
        }
        Group("Облако (ваш GitHub)") {
            val cctx = androidx.compose.ui.platform.LocalContext.current
            val cs by com.vlesscardvpn.core.CloudSync.state.collectAsState()
            var repo by remember { mutableStateOf(com.vlesscardvpn.core.CloudSync.repo(cctx)) }
            var token by remember { mutableStateOf("") }
            var on by remember { mutableStateOf(com.vlesscardvpn.core.CloudSync.configured(cctx)) }
            Text("Маски, обход DPI, результаты серверов и мини-нейросеть сохраняются в ваш закрытый репозиторий GitHub (раз в 3 часа, если что-то изменилось) и возвращаются на новом телефоне или после переустановки — заново учить маски не надо. " +
                "Нужен токен GitHub (Fine-grained, доступ только к этому репозиторию, Contents: Read and write). На телефоне токен шифруется ключом Android Keystore. Копия в GitHub переносимая, но не зашифрованная: в ней могут быть ключи серверов и ссылки подписок. Используйте только закрытый репозиторий.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.OutlinedTextField(repo, { repo = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Репозиторий (имя/репо)") })
            androidx.compose.material3.OutlinedTextField(token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), label = { Text(if (on) "Токен сохранён (ввести новый)" else "Токен GitHub") })
            if (cs.text.isNotEmpty()) Text(cs.text, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val err = com.vlesscardvpn.core.CloudSync.save(cctx, repo, token)
                    if (err != null) com.vlesscardvpn.core.CloudSync.state.value = com.vlesscardvpn.core.CloudSync.State(err)
                    else { token = ""; on = com.vlesscardvpn.core.CloudSync.configured(cctx); if (on) com.vlesscardvpn.core.CloudSync.upload(cctx, force = true) else com.vlesscardvpn.core.CloudSync.state.value = com.vlesscardvpn.core.CloudSync.State("Введите токен") }
                }, enabled = !cs.busy) { Text("Сохранить в облако") }
                if (on) OutlinedButton(onClick = { com.vlesscardvpn.core.CloudSync.restore(cctx) }, enabled = !cs.busy) { Text("Загрузить из облака") }
                if (on) TextButton(onClick = { com.vlesscardvpn.core.CloudSync.forget(cctx); on = false }) { Text("Отключить") }
            }
        }
        Group("Обновление") {
            val upd by com.vlesscardvpn.core.Updater.state.collectAsState()
            val uctx = androidx.compose.ui.platform.LocalContext.current
            Text("Версия ${com.vlesscardvpn.BuildConfig.VERSION_NAME}. Новые версии проверяются при запуске и фоновой задачей Android примерно раз в час. APK скачивается в сети без тарификации, установка — только с вашим подтверждением. Android может отложить фоновую задачу.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var autoApk by remember { mutableStateOf(com.vlesscardvpn.core.Updater.autoDownload(uctx)) }
            Toggle("Скачивать APK автоматически", "Только сеть без тарификации, без перегрева и низкого заряда. Проверяются SHA-256, пакет, версия и сертификат. Можно отключить; установка не запускается сама.", autoApk) {
                autoApk = it; com.vlesscardvpn.core.Updater.setAutoDownload(uctx, it)
            }
            if (upd.text.isNotEmpty()) Text(upd.text, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { com.vlesscardvpn.core.Updater.check(force = true, ctx = uctx) }) { Text("Проверить") }
                if (upd.latest.isNotEmpty()) Button(onClick = { com.vlesscardvpn.core.Updater.install(uctx) }, enabled = !upd.busy) { Text("Обновить до ${upd.latest}") }
            }
        }
        Group("Оформление") {
            Toggle("Море на фоне", "Спокойное морское оформление. AMOLED остаётся чёрным; включение моря переключит его на обычную тёмную тему", s.oceanBackground && s.theme != "amoled") { v ->
                set { it.copy(oceanBackground = v, theme = if (v && it.theme == "amoled") "dark" else it.theme) }
            }
            Toggle("Анимация волн", "Не чаще 20 кадров/с. Замирает в фоне, при экономии энергии, низком заряде, нагреве и выключенной системной анимации; фон остаётся виден", s.oceanMotion) { v -> set { it.copy(oceanMotion = v) } }
            Toggle("Заставка при открытии", "Короткая морская заставка без голоса и звука. Коснитесь, чтобы сразу перейти к приложению. При работающем VPN пропускается", s.welcomeSound) { v -> set { it.copy(welcomeSound = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to "Тёмная", "amoled" to "AMOLED", "you" to "Material You").forEach { (id, t) ->
                    if (id != "you" || Build.VERSION.SDK_INT >= 31) FilterChip(selected = s.theme == id, onClick = { set { it.copy(theme = id) } }, label = { Text(t) })
                }
            }
            Hint("AMOLED — чистый чёрный фон, экономит батарею на OLED-экранах. Material You — цвета ваших обоев (Android 12+).")
        }
        Group("Почему не работает?") {
            val sc = rememberCoroutineScope(); var diag by remember { mutableStateOf("") }; var running by remember { mutableStateOf(false) }
            Hint(diag.ifEmpty { "Находит причину: белые списки, блокировка сервера по IP или по имени (SNI), «заморозка» загрузки после 16 КБ внутри VPN — и подсказывает, что поможет." })
            Button(onClick = { running = true; sc.launch { diag = com.vlesscardvpn.core.Diagnose.report(Store.state.value.selected, com.vlesscardvpn.core.Tunnel.socks?.port); running = false } },
                enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Проверяю… (~15 с)" else "Найти причину") }
        }
        // ---------------- DPI
        Group("Обход DPI без сервера (ByeDPI + zapret)") {
        val network = remember { Net.key(ctx) }
        val current = DpiStrategies.resolve(s, network)
        Hint("Используется в режимах «ByeDPI» и «Гибрид» и как маскировка «через ByeDPI» для серверов. Сейчас: ${current.label} (сеть «$network»).")
        Button(onClick = { Actions.findDpi() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Подобрать обход для этой сети") }
        LiveProgress { it.title == "Подбор обхода DPI" || it.title == "Проверка обхода DPI" || it.title.startsWith("Финал") }
        val results by Actions.dpiResults.collectAsState()
        var showAll by remember { mutableStateOf(false) }
        StrategyRow("Авто — подобранная для сети", s.dpiStrategy == Settings.DPI_AUTO, null) { set { it.copy(dpiStrategy = Settings.DPI_AUTO) } }
        StrategyRow("Своя строка (ниже)", s.dpiStrategy == DpiStrategies.CUSTOM_ID, results[DpiStrategies.CUSTOM_ID]) { set { it.copy(dpiStrategy = DpiStrategies.CUSTOM_ID) } }
        val list = if (showAll) DpiStrategies.BUILT_IN else DpiStrategies.BUILT_IN.filter { it.own || results[it.id]?.ok == true || s.dpiStrategy == it.id }
        list.forEach { st -> StrategyRow(st.label, s.dpiStrategy == st.id, results[st.id]) { set { it.copy(dpiStrategy = st.id) } } }
        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Свернуть" else "Все ${DpiStrategies.BUILT_IN.size} стратегий") }
        var args by remember(s.byeDpiArgs) { mutableStateOf(s.byeDpiArgs) }
        val parsed = ByeDpiArgs.parse(args, s.byeDpiSni)
        OutlinedTextField(args, { v -> args = v; if (ByeDpiArgs.parse(v, s.byeDpiSni).isSuccess) set { it.copy(byeDpiArgs = v) } },
            label = { Text("Своя стратегия (строка из ByeByeDPI)") }, modifier = Modifier.fillMaxWidth(),
            isError = parsed.isFailure, supportingText = {
                val w = parsed.getOrNull()?.let { ByeDpiArgs.warnings(it) }.orEmpty()
                (parsed.exceptionOrNull()?.message ?: w.firstOrNull())?.let { Text(it) }
            })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(Settings.DEFAULT_BYEDPI) + ByeDpiArgs.EXAMPLES).distinct().forEach { e ->
                AssistChip(onClick = { args = e; set { it.copy(byeDpiArgs = e) } }, label = { Text(e, fontSize = 11.sp) })
            }
        }
        Hint("Домен маскировки (фейковый ClientHello: провайдер видит этот сайт)")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ByeDpiArgs.MASK_DOMAINS.forEach { d -> FilterChip(selected = s.byeDpiSni == d, onClick = { set { it.copy(byeDpiSni = d) } }, label = { Text(d) }) }
        }
        OutlinedButton(onClick = { Actions.testByeDpi() }, enabled = !busy) { Text("Проверить текущую") }
        var hybrid by remember(s.hybridDomains) { mutableStateOf(s.hybridDomains.joinToString(", ")) }
        OutlinedTextField(hybrid, { v -> hybrid = v; set { it.copy(hybridDomains = v.split(',', ' ', '\n').map { d -> d.trim() }.filter { d -> d.isNotEmpty() }) } },
            label = { Text("Гибрид: эти сайты — через обход DPI (geosite:… или домены)") }, modifier = Modifier.fillMaxWidth())

        // ---------------- hiding
        }
        Group("Скрытность") {
        Toggle("Скрытый локальный прокси", "Случайный порт и пароль при каждом подключении — приложения не найдут прокси сканированием 10808 и не смогут им воспользоваться", s.stealthSocks) { v -> set { it.copy(stealthSocks = v) } }
        if (!s.stealthSocks) {
            var port by remember(s.socksPort) { mutableStateOf(s.socksPort.toString()) }
            OutlinedTextField(port, { v -> port = v.filter { it.isDigit() }.take(5); port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> set { it.copy(socksPort = p) } } },
                label = { Text("Порт SOCKS 127.0.0.1 (без пароля)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Toggle("Российские сайты напрямую", "Банки, Госуслуги, VK, Яндекс видят обычный российский IP, а не зарубежный VPN", s.ruDirect) { v -> set { it.copy(ruDirect = v) } }
        if (s.ruDirect) Toggle("DNS для .ru — Яндекс", "Российские домены резолвятся как у обычного абонента; остальное — зашифрованным DNS через туннель", s.ruDns) { v -> set { it.copy(ruDns = v) } }
        Toggle("Блокировать WebRTC/STUN", "Сайты не узнают адрес через WebRTC. Может сломать звонки WhatsApp/Discord", s.blockStun) { v -> set { it.copy(blockStun = v) } }
        Toggle("Блокировать QUIC (UDP 443)", "Браузеры и YouTube пойдут по TCP+TLS, который маскируется. В режиме ByeDPI включено всегда", s.blockQuic) { v -> set { it.copy(blockQuic = v) } }
        Hint("Маскировка поведения и устройства — не только пакеты")
        Toggle("Российские приложения мимо VPN", "Сбер, Т-Банк, ВТБ, Госуслуги, Ozon, WB, Яндекс, VK, операторы… идут напрямую: видят обычного абонента и не ругаются на VPN", s.ruAppsDirect) { v -> set { it.copy(ruAppsDirect = v) } }
        Toggle("Случайный адрес VPN-интерфейса", "Новый внутренний адрес при каждом подключении и нейтральное имя сеанса — нет постоянных признаков, по которым ищут VPN-клиенты", s.randomTun) { v -> set { it.copy(randomTun = v) } }
        Toggle("Фон обычного пользователя", "Пока VPN включён, изредка (раз в 1–3 мин, ≤32 КБ) открывает обычный российский сайт напрямую: у провайдера обычная картина, а не один поток за границу", s.coverTraffic) { v -> set { it.copy(coverTraffic = v) } }
        OutlinedButton(onClick = { set { it.copy(stealthSocks = true, ruDirect = true, ruDns = true, blockQuic = true, rotateMasks = true, ruAppsDirect = true, randomTun = true, coverTraffic = true, autoHeal = true, maskEvolution = true) } }) {
            Text("Включить всю маскировку")
        }
        Toggle("Нейтральное уведомление", "В шторке «Синхронизация · Активно» вместо названия VPN", s.quietNotification) { v -> set { it.copy(quietNotification = v) } }
        Hint("Иконка и название на рабочем столе")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Apps.DISGUISES.forEach { d ->
                FilterChip(selected = s.disguise == d.id, onClick = { set { it.copy(disguise = d.id) }; Apps.applyDisguise(ctx, d.id) }, label = { Text(d.title) })
            }
        }
        OutlinedButton(onClick = { appsDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.apps.isEmpty()) "Приложения без VPN (банки, Госуслуги…)" else if (s.onlyApps) "Только через VPN: ${s.apps.size} прил." else "Без VPN: ${s.apps.size} прил.")
        }
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Kill switch: «Постоянная VPN» + «Блокировать без VPN»") }
        Hint("В системных настройках VPN нажмите ⚙ у VLESS Card и включите оба переключателя: при обрыве интернет не пойдёт мимо VPN.")
        if (s.alwaysVpn.isNotEmpty() || s.alwaysDirect.isNotEmpty()) {
            Section("Сайты: всегда через VPN / напрямую")
            Hint("Добавляются кнопкой на главном экране «Сайт не открывается?». Нажмите, чтобы убрать (действует со следующего подключения).")
            (s.alwaysVpn.map { it to "через VPN" } + s.alwaysDirect.map { it to "напрямую" }).forEach { (h, w) ->
                TextButton(onClick = { Actions.routeSite(h, "") }) { Text("✕  $h — $w", fontSize = 13.sp) }
            }
        }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                "Резервная копия VLESS Card (вставьте в приложении: Серверы → Добавить). Внутри ключи серверов — не пересылайте чужим:\n" + Actions.backup()), "Сохранить резервную копию"))
        }, modifier = Modifier.fillMaxWidth()) { Text("Резервная копия (в Избранное / заметки)") }
        Hint("Копия не зашифрована и содержит ключи серверов и ссылки подписок — храните её только у себя. Настройки, свои серверы и WARP, выученные маскировки и обходы — одной ссылкой. Для нового телефона или после переустановки.")
        OutlinedButton(onClick = { wipeDialog = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad), modifier = Modifier.fillMaxWidth()) { Text("Стереть все данные") }

        // ---------------- servers
        }
        Group("Tor — отдельный путь (встроенный, без Termux)") {
            Hint("Tor встроен в приложение: Termux не нужен. Выбранные сервисы используют локальный Tor. Встроенный демон работает от UID приложения: автоматическая обёртка его соединений в VLESS не гарантируется.")
            Toggle("Tor для сервисов", "Выбранные сервисы идут через локальный Tor. Это не подтверждает доступность Discord-голоса: Tor передаёт TCP, но не произвольный UDP.", s.torRouting) { v -> set { it.copy(torRouting = v) } }
            Toggle("Tor в Авто", "Если сервис перестаёт работать через все серверы — сам переключаю его на Tor, пока связь не восстановится", s.torAuto) { v -> set { it.copy(torAuto = v) } }
            Toggle("Отдельный путь Tor", "Демон Tor ходит в интернет сам, минуя VPN: своё шифрование и мосты, работает даже при недоступных серверах", s.torDirect) { v -> set { it.copy(torDirect = v) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("none" to "Без мостов", "obfs4" to "obfs4", "webtunnel" to "WebTunnel").forEach { (id, t) ->
                    FilterChip(selected = s.torBridge == id, onClick = { set { it.copy(torBridge = id) } }, label = { Text(t) })
                }
            }
            if (s.torBridge !in com.vlesscardvpn.tor.TorCard.EMBEDDED_BRIDGES) Hint("Выбранный транспорт не встроен. Выберите obfs4, WebTunnel или режим без мостов; автоматического перехода на прямой Tor нет.")
            if (s.torBridge != "none") {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://bridges.torproject.org/options"))) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Получить мосты (bridges.torproject.org)") }
                var bridges by remember(s.torBridgeLines) { mutableStateOf(s.torBridgeLines) }
                val bridgeScope=rememberCoroutineScope()
                var bridgeLoading by remember { mutableStateOf(false) }
                var bridgeInfo by remember { mutableStateOf("") }
                OutlinedButton(enabled=!bridgeLoading, onClick={
                    val transport=s.torBridge
                    bridgeLoading=true;bridgeInfo=""
                    bridgeScope.launch {
                        try {
                            val catalog=com.vlesscardvpn.tor.TorBridgeCatalog.fetch()
                            val imported=catalog.forTransport(transport)
                            if(Store.settings.value.torBridge!=transport) bridgeInfo="Режим изменён: мосты не заменены."
                            else if(imported.isEmpty()) bridgeInfo="Нет корректных мостов выбранного транспорта; сохранённые строки не изменены."
                            else {
                                bridges=imported.joinToString("\n")
                                set { it.copy(torBridgeLines=bridges) }
                                bridgeInfo="Добавлено ${imported.size}; неподдерживаемых/некорректных строк ${catalog.rejected}. Доступность ещё не проверена."
                            }
                        } catch(e: Exception) {
                            if(e is kotlinx.coroutines.CancellationException) throw e
                            bridgeInfo="Источник недоступен или список некорректен. Сохранённые мосты не изменены."
                        } finally { bridgeLoading=false }
                    }
                },modifier=Modifier.fillMaxWidth()) { Text(if(bridgeLoading) "Загружаю список…" else "Загрузить мосты igareck (выбранный транспорт)") }
                Hint("Ручная загрузка из публичного TOR_BRIDGES_TOP100.txt. Источник увидит запрос; список не доказывает доступность или скорость мостов. Автообновления и сканирования нет.")
                if(bridgeInfo.isNotBlank()) Hint(bridgeInfo)
                androidx.compose.material3.OutlinedTextField(bridges, { v -> bridges = v; set { it.copy(torBridgeLines = v) } },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Мосты: «Bridge …» или строки из списка (до 12)") }, minLines = 2, maxLines = 6)
                Hint("Поддерживаются obfs4 и WebTunnel, с префиксом Bridge или без него. Некорректные строки и другой транспорт не применяются; прямой Tor автоматически не включается. Применится при следующем подключении.")
            }
            Toggle("Весь трафик через Tor", "Направлять поддерживаемый TCP-трафик через Tor. UDP не поддерживается; абсолютная анонимность и цепочка Tor поверх VLESS не гарантируются.", s.torAll) { v -> set { it.copy(torAll = v) } }
        }
        Group("Нагрузка VPN и батарея") {
        val power by com.vlesscardvpn.core.PhonePower.state.collectAsState()
        val sensors by com.vlesscardvpn.core.PhonePower.sensors.collectAsState()
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { com.vlesscardvpn.core.PhonePower.refresh(ctx); kotlinx.coroutines.delay(10000) } } }
        Text(power.reason, color = MaterialTheme.colorScheme.primary)
        Hint(sensors)
        Hint("Лимиты проверок: TCP ${power.tcpParallel}, реальные ${power.realParallel}. VPN и аварийное восстановление не отключаются. Это не физическое охлаждение и не ускоритель других приложений.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("balanced" to "Баланс", "eco" to "Экономия", "fast" to "Быстрее").forEach { (id, title) ->
                FilterChip(selected = s.powerProfile == id, onClick = { set { it.copy(powerProfile = id) } }, label = { Text(title) })
            }
        }
        Hint("Профиль «Быстрее» разрешает больше одновременных проверок, но не отменяет защиту батареи и нагрева.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(10, 20, 30).forEach { n -> FilterChip(selected = s.batteryThreshold == n,
                onClick = { set { it.copy(batteryThreshold = n) } }, label = { Text("Заряд ≤ $n%") }) }
        }
        Toggle("Тихо с выключенным экраном", "Необязательные задачи спят; при включении экрана связь проверяется снова. Не зависит от крупных пакетов", s.quietScreen) { v -> set { it.copy(quietScreen = v) } }
        Toggle("Улучшения только на зарядке", "Поиск более быстрых серверов и эволюция ждут зарядку. Восстановление связи — всегда", s.chargeOnly) { v -> set { it.copy(chargeOnly = v) } }
        Toggle("Фоновые улучшения без тарификации", "Не тратить мобильный трафик на необязательные проверки; системная экономия трафика учитывается всегда", s.wifiMaintenance) { v -> set { it.copy(wifiMaintenance = v) } }
        Toggle("Не мешать активному трафику", "Отложить фоновые тесты при потоке свыше 40 КБ/с. Это оценка трафика, а не распознавание звонков", s.protectActiveTraffic) { v -> set { it.copy(protectActiveTraffic = v) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 60).forEach { n -> OutlinedButton(onClick = { com.vlesscardvpn.core.PhonePower.pause(n); com.vlesscardvpn.core.PhonePower.refresh(ctx) }) { Text("Пауза $n мин") } }
            TextButton(onClick = { com.vlesscardvpn.core.PhonePower.resume(); com.vlesscardvpn.core.PhonePower.refresh(ctx) }) { Text("Возобновить") }
        }
        Hint("Пауза касается только улучшений, не VPN; после перезапуска приложения сбрасывается. При зарядке тепловая защита начинается раньше (39 °C батареи), возврат — после двух минут устойчивого остывания. Критический статус Android ограничивает нагрузку всегда.")
        }
        Group("Несколько серверов одновременно") {
        Hint("Запросы распределяются между отмеченными серверами; неработающие исключаются автоматически (проверка раз в минуту).")
        Balance.values().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = s.balance == b, onClick = { set { it.copy(balance = b) } }); Text(b.title) }
        }
        Toggle("Мультиплексирование (mux)", "Меньше новых соединений — меньше рукопожатий, которые видит DPI. Не для Vision/XHTTP", s.mux) { v -> set { it.copy(mux = v) } }
        Toggle("Менять маскировку при каждом подключении", "Случайная из рабочих, найденных «Подобрать маскировку», — у трафика нет постоянного отпечатка", s.rotateMasks) { v -> set { it.copy(rotateMasks = v) } }
        Toggle("Блокировать рекламу и трекеры", "Блокирует отдельные рекламные домены. Рекламу внутри видео YouTube гарантированно не удаляет: она может идти с теми же адресами, что и видео. При сбое сайта выключите", s.blockAds) { v -> set { it.copy(blockAds = v) } }
        Toggle("Режим «Не отслеживается»", "Режет мобильную телеметрию (Firebase/Crashlytics/Sentry/AppsFlyer/TikTok-аналитика) и DoT 853 — приложения не могут обойти DNS приложения и слить телеметрию мимо VPN", s.blockTrackers) { v -> set { it.copy(blockTrackers = v) } }
        Toggle("Ускорение телефона", "Кэш DNS снижает повторные ожидания. MTU выбирается по сети (1280–1400), без гигантских пакетов. Это оптимизация VPN, не ускорение других приложений", s.turbo) { v -> set { it.copy(turbo = v) } }
        var lim by remember(s.monthLimitGb) { mutableStateOf(if (s.monthLimitGb > 0) s.monthLimitGb.toString() else "") }
        OutlinedTextField(lim, { v -> lim = v.filter { it.isDigit() }.take(5); set { it.copy(monthLimitGb = lim.toIntOrNull() ?: 0) } }, singleLine = true,
            label = { Text("Лимит трафика в месяц, ГБ (пусто — без лимита)") }, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(com.vlesscardvpn.core.Traffic.line(s).first.ifEmpty { "На главном экране покажу, сколько ушло через VPN за месяц, и предупрежу у лимита" }) })
        Toggle("Подключаться после перезагрузки", "Телефон включился — VPN включится сам (если разрешение уже дано)", s.autoStart) { v -> set { it.copy(autoStart = v) } }
        Toggle("Умный YouTube", "Авто: в каждой сети сам сравнивает скорость YouTube через серверы и через обход DPI напрямую к Google. Если обход заметно быстрее — YouTube идёт им, остальное через серверы. Сломается — сам вернёт серверы", s.smartYoutube) { v -> set { it.copy(smartYoutube = v, ytDpi = emptyMap()) } }
        Toggle("Автопилот помощника", "Пока VPN включён, помощник сам следит за связью: пропал интернет — чинит (серверы, маски, обход DPI), маска тяжёлая — ищет лёгкую. Обо всём пишет во вкладке «Помощник»", s.autopilot) { v -> set { it.copy(autopilot = v) } }
        Toggle("Охлаждение", "Снижает только нагрузку VPN: пауза необязательных задач, ограничение параллелизма, возврат после устойчивого остывания. Настройки пакетов и масок не меняются; критическая защита действует всегда", s.autoCool) { v -> set { it.copy(autoCool = v) } }
        Toggle("Под приложение", "Видит, что открыто: Gemini, ChatGPT, Claude, Spotify, Netflix — сам переключит на серверы страны, где они работают (США, Нидерланды…); YouTube — ускорит видео для этой сети", s.appAware) { v -> set { it.copy(appAware = v) } }
        if (s.appAware && !com.vlesscardvpn.core.AppWatch.hasAccess(ctx)) TextButton(onClick = { com.vlesscardvpn.core.AppWatch.openAccess(ctx) }) { Text("Разрешить «Доступ к истории использования»") }
        Toggle("GPS под страну сервера", "Пока включён VPN, телефон «окажется» в столице страны сервера (подмена местоположения Android). Нужно выбрать VLESS Card в Параметрах разработчика → «Приложение для фиктивных местоположений»", s.gpsSpoof) { v -> set { it.copy(gpsSpoof = v) }; if (!v) com.vlesscardvpn.core.GpsMock.stop(ctx) }
        if (s.gpsSpoof) {
            val g by com.vlesscardvpn.core.GpsMock.state.collectAsState()
            if (g.isNotEmpty()) Text(g, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { com.vlesscardvpn.core.GpsMock.openDevSettings(ctx) }) { Text("Открыть параметры разработчика") }
        }
        Toggle("Фоновое ускорение", "Само готовит серверы и маски при запуске, а при подключении раз в 20 минут проверяет и переходит на более быстрые. На мобильном — экономно", s.autoOptimize) { v -> set { it.copy(autoOptimize = v) } }
        Toggle("Самовосстановление", "Если проверка после подключения не прошла: другая рабочая маскировка → новый подбор → (Авто) обход DPI без сервера", s.autoHeal) { v -> set { it.copy(autoHeal = v) } }

        }
        Group("Через VPN только эти сервисы") {
        Hint("Ничего не отмечено — через VPN весь трафик. Отмечено — только эти сервисы, остальное напрямую (быстрее, банки и Госуслуги видят обычный IP).")
        com.vlesscardvpn.core.Services.ALL.forEach { sv ->
            Row(Modifier.fillMaxWidth().clickable { set { it.copy(services = if (sv.id in it.services) it.services - sv.id else it.services + sv.id) } },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(sv.id in s.services, { v -> set { it.copy(services = if (v) (it.services + sv.id).distinct() else it.services - sv.id) } })
                Text(sv.title, fontSize = 14.sp)
            }
        }

        }
        Group("Режим «Прокси»") {
        Hint("Без VPN-значка: приложения сами ходят через SOCKS5 127.0.0.1:${s.socksPort} или HTTP 127.0.0.1:${s.httpPort} (Telegram, браузеры, торренты).")
        var port by remember(s.socksPort) { mutableStateOf(s.socksPort.toString()) }
        OutlinedTextField(port, { v -> port = v; v.toIntOrNull()?.takeIf { it in 1024..65534 }?.let { p -> set { it.copy(socksPort = p) } } },
            label = { Text("Порт SOCKS5 (HTTP = +1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Toggle("Раздавать в Wi-Fi / точку доступа", "Другие устройства подключаются к IP телефона (без пароля — только в своей сети)", s.lanShare) { v -> set { it.copy(lanShare = v) } }
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tg://socks?server=127.0.0.1&port=${s.socksPort}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Добавить прокси в Telegram") }

        }
        Group("WARP") {
        Toggle("WARP через сервер", "Телефон → ваш сервер (с маскировкой) → Cloudflare WARP → интернет. Работает, даже когда оператор режет WARP напрямую. Сайты видят адрес Cloudflare — меньше капч и блокировок по IP сервера. Нужны аккаунт WARP (Серверы → «WARP») и хотя бы один обычный сервер", s.warpChain) { v -> set { it.copy(warpChain = v) } }
        Hint("Серверы → «WARP» создаёт аккаунты и подбирает точки входа. Ключ WARP+ ускоряет WARP (вставьте один или целый пост с ключами).")
        var wk by remember(s.warpKeys) { mutableStateOf(s.warpKeys) }
        OutlinedTextField(wk, { v -> wk = v; set { it.copy(warpKeys = v) } }, label = { Text("Свой ключ WARP+ (xxxxxxxx-xxxxxxxx-xxxxxxxx)") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 140.dp))
        if (s.warpKeys.isNotBlank()) Hint("Найдено ключей: ${com.vlesscardvpn.core.WarpKeys.parse(s.warpKeys).size}")
        Toggle("Встроенные ключи WARP+", "${com.vlesscardvpn.core.WarpKeys.BUILT_IN.size} публичных ключей: пробуются по очереди, если своего нет или он занят", s.warpBuiltinKeys) { v -> set { it.copy(warpBuiltinKeys = v) } }
        var keysDialog by remember { mutableStateOf(false) }
        val okKeys = (com.vlesscardvpn.core.WarpKeys.parse(s.warpKeys) + com.vlesscardvpn.core.WarpKeys.BUILT_IN).distinct()
            .count { com.vlesscardvpn.core.WarpKeys.status(s.warpKeyStatus, it) == com.vlesscardvpn.core.WarpKeys.OK }
        OutlinedButton(onClick = { keysDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Ключи WARP+: выбрать и включить" + if (okKeys > 0) " (рабочих: $okKeys)" else "")
        }
        if (keysDialog) WarpKeysDialog(onDismiss = { keysDialog = false })
        OutlinedButton(onClick = { Actions.setupWarp() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Настроить WARP сейчас") }

        }
        MyMasksSection()

        Group("Подбор маскировки: что перебирать") {
        Hint("Ничего не отмечено — все виды. Меньше видов — быстрее подбор. Сервер перестаёт перебираться после 3 рабочих масок.")
        com.vlesscardvpn.xray.Masks.FAMILIES.forEach { (id, title) ->
            Row(Modifier.fillMaxWidth().clickable { set { it.copy(maskFamilies = if (id in it.maskFamilies) it.maskFamilies - id else it.maskFamilies + id) } },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(id in s.maskFamilies, { v -> set { it.copy(maskFamilies = if (v) (it.maskFamilies + id).distinct() else it.maskFamilies - id) } })
                Text(title, fontSize = 14.sp)
            }
        }
        Hint("Отпечатки браузера (TLS)")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.vlesscardvpn.xray.Masks.FINGERPRINTS.forEach { fp ->
                FilterChip(selected = fp in s.maskFps, onClick = { set { it.copy(maskFps = if (fp in it.maskFps) it.maskFps - fp else it.maskFps + fp) } }, label = { Text(fp) })
            }
        }

        }
        Group("DNS через туннель") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Settings.DNS_PRESETS.forEach { (n, u) -> FilterChip(selected = s.dnsUrl == u, onClick = { set { it.copy(dnsUrl = u) } }, label = { Text(n) }) }
        }

        }
        Group("Подписки") {
        Toggle("Обновлять автоматически", "При запуске и перед «Авто», если старше 12 часов", s.autoUpdateSubs) { v -> set { it.copy(autoUpdateSubs = v) } }
        Hint("Отметьте источники (${s.subscriptions.size} выбрано). Белые списки — для мобильного интернета, когда открываются только российские сайты.")
        com.vlesscardvpn.model.Subs.CATALOG.groupBy { it.group }.forEach { (group, items) ->
            Text(group, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            items.forEach { src ->
                Row(Modifier.fillMaxWidth().clickable {
                    set { it.copy(subscriptions = if (src.url in it.subscriptions) it.subscriptions - src.url else it.subscriptions + src.url) }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(src.url in s.subscriptions, { v -> set { it.copy(subscriptions = if (v) (it.subscriptions + src.url).distinct() else it.subscriptions - src.url) } })
                    Text(src.title, fontSize = 13.sp)
                }
            }
        }
        val catalog = remember { com.vlesscardvpn.model.Subs.CATALOG.map { it.url }.toSet() }
        s.subInfo.filter { it.key in s.subscriptions }.forEach { (u, i) ->
            val line = Actions.subLine(i)
            if (line.isNotEmpty()) Hint("«${i.title.ifEmpty { com.vlesscardvpn.model.Subs.title(u) }}»: $line")
        }
        var subs by remember(s.subscriptions) { mutableStateOf(s.subscriptions.filter { it !in catalog }.joinToString("\n")) }
        OutlinedTextField(subs, { v -> subs = v; set { it.copy(subscriptions = it.subscriptions.filter { u -> u in catalog } + v.lines().map { l -> l.trim() }.filter { l -> l.startsWith("http") }) } },
            label = { Text("Свои ссылки подписок, по одной в строке") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { set { it.copy(subscriptions = Settings.DEFAULT_SUBSCRIPTIONS) } }) { Text("Стандартные") }
            TextButton(onClick = { set { it.copy(subscriptions = (com.vlesscardvpn.model.Subs.CATALOG.map { c -> c.url } + it.subscriptions).distinct()) } }) { Text("Все") }
            TextButton(onClick = { Actions.refreshSubscriptions() }, enabled = !busy) { Text("Обновить сейчас") }
        }

        }
        Group("Проверка") {
        var url by remember(s.testUrl) { mutableStateOf(s.testUrl) }
        OutlinedTextField(url, { v -> url = v; if (v.startsWith("https://")) set { it.copy(testUrl = v) } }, label = { Text("Адрес для пинга") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Text(XrayCore.version(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (appsDialog) AppsDialog(s, onDismiss = { appsDialog = false }) { apps, only -> set { it.copy(apps = apps, onlyApps = only) } }
    if (wipeDialog) AlertDialog(onDismissRequest = { wipeDialog = false },
        title = { Text("Стереть всё?") }, text = { Text("VPN отключится, удалятся локальные серверы, подписки, обучение, токены и настройки. Копии, которые вы сохраняли в GitHub или отправляли в заметки, не удаляются.") },
        confirmButton = { TextButton(onClick = { wipeDialog = false; com.vlesscardvpn.vpn.TunnelService.stop(ctx); Actions.wipe(); Apps.applyDisguise(ctx, "") }) { Text("Стереть", color = Bad) } },
        dismissButton = { TextButton(onClick = { wipeDialog = false }) { Text("Отмена") } })
}

@Composable
fun AppsDialog(s: Settings, onDismiss: () -> Unit, onSave: (List<String>, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val all = remember { Apps.launcherApps(ctx) }
    var chosen by remember { mutableStateOf(s.apps.toSet()) }
    var only by remember { mutableStateOf(s.onlyApps) }
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (only) "Только эти — через VPN" else "Эти — без VPN") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Режим «только отмеченные через VPN»", Modifier.weight(1f), fontSize = 13.sp); Switch(only, { only = it })
                }
                TextButton(onClick = { chosen = chosen + all.map { it.pkg }.filter { it in Apps.RU_SENSITIVE }; only = false }) {
                    Text("Отметить банки, Госуслуги, маркетплейсы, VK, Яндекс")
                }
                OutlinedTextField(q, { q = it }, label = { Text("Поиск") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(all.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }, key = { it.pkg }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (a.pkg in chosen) chosen - a.pkg else chosen + a.pkg },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(a.pkg in chosen, { chosen = if (it) chosen + a.pkg else chosen - a.pkg })
                            Column { Text(a.label, fontSize = 14.sp); Text(a.pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen.toList().sorted(), only); onDismiss() }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun StrategyRow(label: String, selected: Boolean, r: Actions.DpiResult?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        if (r != null) Text(if (!r.ok) "✗" else if (r.score >= 0) "✓ ${r.score}/${Tester.DPI_SITES.size * 2} · ${r.ms} мс" else "✓ ${r.ms} мс", color = if (r.ok) Good else Bad, fontSize = 12.sp)
    }
}

/** One UI-style group: a rounded card with a title; tap the title to fold it (the state is kept while the app lives). */
private val folded = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
@Composable private fun Group(t: String, content: @Composable ColumnScope.() -> Unit) {
    val open = folded[t] != true
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { folded[t] = open }, verticalAlignment = Alignment.CenterVertically) {
                Text(t, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(if (open) "▴" else "▾", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (open) content()
        }
    }
}
@Composable private fun Section(t: String) = Text(t, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
@Composable private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun Toggle(t: String, hint: String?, v: Boolean, on: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(t, fontSize = 14.sp); if (hint != null) Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(v, on)
    }
