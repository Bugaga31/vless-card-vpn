package com.vlesscardvpn.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
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
        // ---------------- DPI
        Section("Обход DPI без сервера (ByeDPI + zapret)")
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
        Section("Скрытность")
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
        OutlinedButton(onClick = { wipeDialog = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Bad), modifier = Modifier.fillMaxWidth()) { Text("Стереть все данные") }

        // ---------------- servers
        Section("Несколько серверов одновременно")
        Hint("Запросы распределяются между отмеченными серверами; неработающие исключаются автоматически (проверка раз в минуту).")
        Balance.values().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = s.balance == b, onClick = { set { it.copy(balance = b) } }); Text(b.title) }
        }
        Toggle("Мультиплексирование (mux)", "Меньше новых соединений — меньше рукопожатий, которые видит DPI. Не для Vision/XHTTP", s.mux) { v -> set { it.copy(mux = v) } }
        Toggle("Менять маскировку при каждом подключении", "Случайная из рабочих, найденных «Подобрать маскировку», — у трафика нет постоянного отпечатка", s.rotateMasks) { v -> set { it.copy(rotateMasks = v) } }
        Toggle("Блокировать рекламу", null, s.blockAds) { v -> set { it.copy(blockAds = v) } }
        Toggle("Фоновое ускорение", "Само готовит серверы и маски при запуске, а при подключении раз в 20 минут проверяет и переходит на более быстрые. На мобильном — экономно", s.autoOptimize) { v -> set { it.copy(autoOptimize = v) } }
        Toggle("Самовосстановление", "Если проверка после подключения не прошла: другая рабочая маскировка → новый подбор → (Авто) обход DPI без сервера", s.autoHeal) { v -> set { it.copy(autoHeal = v) } }

        Section("Через VPN только эти сервисы")
        Hint("Ничего не отмечено — через VPN весь трафик. Отмечено — только эти сервисы, остальное напрямую (быстрее, банки и Госуслуги видят обычный IP).")
        com.vlesscardvpn.core.Services.ALL.forEach { sv ->
            Row(Modifier.fillMaxWidth().clickable { set { it.copy(services = if (sv.id in it.services) it.services - sv.id else it.services + sv.id) } },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(sv.id in s.services, { v -> set { it.copy(services = if (v) (it.services + sv.id).distinct() else it.services - sv.id) } })
                Text(sv.title, fontSize = 14.sp)
            }
        }

        Section("Режим «Прокси»")
        Hint("Без VPN-значка: приложения сами ходят через SOCKS5 127.0.0.1:${s.socksPort} или HTTP 127.0.0.1:${s.httpPort} (Telegram, браузеры, торренты).")
        var port by remember(s.socksPort) { mutableStateOf(s.socksPort.toString()) }
        OutlinedTextField(port, { v -> port = v; v.toIntOrNull()?.takeIf { it in 1024..65534 }?.let { p -> set { it.copy(socksPort = p) } } },
            label = { Text("Порт SOCKS5 (HTTP = +1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Toggle("Раздавать в Wi-Fi / точку доступа", "Другие устройства подключаются к IP телефона (без пароля — только в своей сети)", s.lanShare) { v -> set { it.copy(lanShare = v) } }
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("tg://socks?server=127.0.0.1&port=${s.socksPort}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, modifier = Modifier.fillMaxWidth()) { Text("Добавить прокси в Telegram") }

        Section("WARP")
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

        MyMasksSection()

        Section("Подбор маскировки: что перебирать")
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

        Section("DNS через туннель")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Settings.DNS_PRESETS.forEach { (n, u) -> FilterChip(selected = s.dnsUrl == u, onClick = { set { it.copy(dnsUrl = u) } }, label = { Text(n) }) }
        }

        Section("Подписки")
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
        var subs by remember(s.subscriptions) { mutableStateOf(s.subscriptions.filter { it !in catalog }.joinToString("\n")) }
        OutlinedTextField(subs, { v -> subs = v; set { it.copy(subscriptions = it.subscriptions.filter { u -> u in catalog } + v.lines().map { l -> l.trim() }.filter { l -> l.startsWith("http") }) } },
            label = { Text("Свои ссылки подписок, по одной в строке") }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { set { it.copy(subscriptions = Settings.DEFAULT_SUBSCRIPTIONS) } }) { Text("Стандартные") }
            TextButton(onClick = { set { it.copy(subscriptions = (com.vlesscardvpn.model.Subs.CATALOG.map { c -> c.url } + it.subscriptions).distinct()) } }) { Text("Все") }
            TextButton(onClick = { Actions.refreshSubscriptions() }, enabled = !busy) { Text("Обновить сейчас") }
        }

        Section("Проверка")
        var url by remember(s.testUrl) { mutableStateOf(s.testUrl) }
        OutlinedTextField(url, { v -> url = v; if (v.startsWith("https://")) set { it.copy(testUrl = v) } }, label = { Text("Адрес для пинга") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(XrayCore.version(), fontSize = 12.sp, color = Color.Gray)
    }
    if (appsDialog) AppsDialog(s, onDismiss = { appsDialog = false }) { apps, only -> set { it.copy(apps = apps, onlyApps = only) } }
    if (wipeDialog) AlertDialog(onDismissRequest = { wipeDialog = false },
        title = { Text("Стереть всё?") }, text = { Text("VPN отключится, удалятся серверы, подписки, результаты проверок и настройки.") },
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
                            Column { Text(a.label, fontSize = 14.sp); Text(a.pkg, fontSize = 11.sp, color = Color.Gray) }
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

@Composable private fun Section(t: String) = Text(t, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
@Composable private fun Hint(t: String) = Text(t, fontSize = 12.sp, color = Color.Gray)

@Composable private fun Toggle(t: String, hint: String?, v: Boolean, on: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(t, fontSize = 14.sp); if (hint != null) Text(hint, fontSize = 11.sp, color = Color.Gray) }
        Switch(v, on)
    }
