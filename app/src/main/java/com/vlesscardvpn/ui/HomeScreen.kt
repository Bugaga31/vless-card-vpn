package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.BuildConfig
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.Tunnel
import com.vlesscardvpn.model.Mode

@Composable
fun HomeScreen(onConnect: () -> Unit, onDisconnect: () -> Unit, openServers: () -> Unit) {
    val status by Tunnel.status.collectAsState()
    // Only settings and a small summary: the home screen does not redraw for every server result of a check.
    val cfg by Store.settings.collectAsState()
    val sum by Store.summary.collectAsState()
    val busy by Actions.running.collectAsState()
    val working = busy
    val mode = cfg.mode
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("VLESS Card", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("${BuildConfig.VERSION_NAME} · Xray · " + if (cfg.stealthSocks) "прокси скрыт (случайный порт + пароль)" else "SOCKS 127.0.0.1:${cfg.socksPort}",
            fontSize = 12.sp, color = Color.Gray)
        val clip by Actions.clipOffer.collectAsState()
        val hctx = androidx.compose.ui.platform.LocalContext.current
        clip?.let { (text, what) ->
            Card(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2A44))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("В буфере обмена: $what. Добавить?", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { Actions.clipOffer.value = null }) { Text("Нет") }
                    Button(onClick = { Actions.clipOffer.value = null; android.widget.Toast.makeText(hctx, Actions.importAny(text), android.widget.Toast.LENGTH_LONG).show() }) { Text("Добавить") }
                }
            }
        }
        fun reconnect() { if (status.state == Tunnel.State.CONNECTED) onConnect() }
        val on = status.state == Tunnel.State.CONNECTED
        val busy = status.state == Tunnel.State.CONNECTING
        val color = when { on && status.checkOk == false -> Warn; on -> Good; busy -> Accent; else -> Color(0xFF2A2F3A) }
        PowerButton(on, busy, color) { if (on || busy) onDisconnect() else onConnect() }
        if (status.message.isNotEmpty()) Text(status.message, color = if (status.state == Tunnel.State.ERROR) Bad else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        if (status.route.isNotEmpty()) Text(status.route, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        val pausedUntil by Tunnel.pausedUntil.collectAsState()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (status.state == Tunnel.State.CONNECTED) {
            // session: time and traffic through the VPN, refreshed every 5 s only while this screen is open
            var tick by remember { mutableStateOf(0) }
            LaunchedEffect(status.since) { while (true) { kotlinx.coroutines.delay(5000); tick++ } }
            val uid = android.os.Process.myUid()
            val rx = remember(tick) { (android.net.TrafficStats.getUidRxBytes(uid) - Tunnel.rx0).coerceAtLeast(0) }
            val tx = remember(tick) { (android.net.TrafficStats.getUidTxBytes(uid) - Tunnel.tx0).coerceAtLeast(0) }
            val min = remember(tick) { ((System.currentTimeMillis() - status.since) / 60_000).coerceAtLeast(0) }
            // speed right now: bytes since the previous tick (5 s)
            val prev = remember { longArrayOf(rx, tx) }
            val now = remember(tick) { val r = ((rx - prev[0]) * 8 / 5000).coerceAtLeast(0) to ((tx - prev[1]) * 8 / 5000).coerceAtLeast(0); prev[0] = rx; prev[1] = tx; r }
            if (tick > 0 && (now.first > 0 || now.second > 0)) Text("Сейчас: ↓ ${Actions.mbps(now.first.toInt())} ↑ ${Actions.mbps(now.second.toInt())}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            SessionStats(min, rx, tx, cfg.turbo)
            val aw by com.vlesscardvpn.core.AppWatch.status.collectAsState()
            if (aw.isNotEmpty()) Text(aw, fontSize = 12.sp, color = Accent, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            val (month, over) = com.vlesscardvpn.core.Traffic.line(cfg)
            if (month.isNotEmpty()) Text(month, fontSize = 12.sp, color = if (over) Warn else Color.Gray, textAlign = TextAlign.Center)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { Actions.fixAll { onConnect() } }, enabled = !working, label = { Text("🛠 Починить") })
                AssistChip(onClick = { Actions.boostYoutube() }, enabled = !working, label = { Text("▶ Ускорить YouTube") })
                AssistChip(onClick = { Actions.lighten() }, enabled = !working, label = { Text("🪶 Облегчить маски") })
                AssistChip(onClick = { com.vlesscardvpn.vpn.TunnelService.pause(ctx) }, label = { Text("⏸ Пауза 5 мин") })
            }
        } else if (pausedUntil > 0) {
            TextButton(onClick = { onConnect() }) { Text("Включить сейчас") }
        } else if (status.state == Tunnel.State.ERROR) {
            TextButton(onClick = { Actions.fixAll { onConnect() } }, enabled = !working) { Text("Починить и подключить") }
        }
        if (status.check.isNotEmpty()) Text(status.check, color = when (status.checkOk) { true -> Good; false -> Bad; null -> Color.Gray },
            fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (status.state == Tunnel.State.CONNECTING) Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { LiveProgress(stop = false) { it.running } }
        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.height(16.dp))
        var opts by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().clickable { opts = !opts }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Как подключаться", fontWeight = FontWeight.SemiBold)
                        Text(listOf(when (mode) { Mode.AUTO -> "Авто"; Mode.SERVERS -> "Серверы"; Mode.BYEDPI -> "ByeDPI"; Mode.HYBRID -> "Гибрид" },
                            if (cfg.proxyOnly) "Прокси" else "VPN",
                            if (!cfg.perApp || cfg.apps.isEmpty()) "все приложения" else if (cfg.onlyApps) "только ${cfg.apps.size} прил." else "кроме ${cfg.apps.size} прил.",
                            if (cfg.services.isEmpty()) "весь трафик" else com.vlesscardvpn.core.Services.label(cfg.services)).joinToString(" · "),
                            fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Text(if (opts) "▴" else "▾", fontSize = 18.sp, color = Accent)
                }
                if (opts) {
                    Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mode.values().forEach { m ->
                        FilterChip(selected = mode == m, onClick = {
                            Store.update { it.copy(settings = it.settings.copy(mode = m)) }
                            if (m != mode && status.state == Tunnel.State.CONNECTED) onConnect() // reconnect with the new mode
                        },
                            label = { Text(when (m) { Mode.AUTO -> "Авто"; Mode.SERVERS -> "Серверы"; Mode.BYEDPI -> "ByeDPI"; Mode.HYBRID -> "Гибрид" }) })
                    }
                }
                Text(when (mode) {
                    Mode.AUTO -> "Сам обновит подписки, проверит серверы, подберёт маскировку и обход DPI. Если маскировку распознают — сменит её сам"
                    Mode.SERVERS -> "Весь трафик через выбранные серверы (запросы распределяются между ними)"
                    Mode.BYEDPI -> "Без сервера: обход DPI встроенными ByeDPI/zapret (YouTube, Discord и т.п.). Не помогает от блокировки по IP"
                    Mode.HYBRID -> "YouTube/Discord/Telegram — через обход DPI, остальное — через серверы"
                }, fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                val st = cfg
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Тип:", fontSize = 13.sp)
                    FilterChip(selected = !st.proxyOnly, onClick = { if (st.proxyOnly) { onDisconnect(); Store.update { it.copy(settings = it.settings.copy(proxyOnly = false)) } } }, label = { Text("VPN") })
                    FilterChip(selected = st.proxyOnly, onClick = { if (!st.proxyOnly) { onDisconnect(); Store.update { it.copy(settings = it.settings.copy(proxyOnly = true)) } } }, label = { Text("Прокси") })
                }
                if (st.proxyOnly) Text("Без VPN: SOCKS5 127.0.0.1:${st.socksPort}, HTTP :${st.httpPort}" + (if (st.lanShare) " — раздаётся в Wi-Fi" else "") + ". Telegram — кнопка в Настройках",
                    fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                else {
                    var appsDialog by remember { mutableStateOf(false) }
                    val n = st.apps.size
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = !st.perApp || n == 0, onClick = { Store.update { it.copy(settings = it.settings.copy(perApp = false)) }; reconnect() }, label = { Text("Все приложения") })
                        FilterChip(selected = st.perApp && n > 0 && st.onlyApps, onClick = {
                            if (n == 0) appsDialog = true else { Store.update { it.copy(settings = it.settings.copy(perApp = true, onlyApps = true)) }; reconnect() }
                        }, label = { Text(if (n > 0) "Только выбранные ($n)" else "Только выбранные") })
                        FilterChip(selected = st.perApp && n > 0 && !st.onlyApps, onClick = {
                            if (n == 0) appsDialog = true else { Store.update { it.copy(settings = it.settings.copy(perApp = true, onlyApps = false)) }; reconnect() }
                        }, label = { Text("Все, кроме выбранных") })
                        TextButton(onClick = { appsDialog = true }) { Text("Выбрать…") }
                    }
                    if (appsDialog) AppsDialog(st, onDismiss = { appsDialog = false }) { apps, only ->
                        Store.update { it.copy(settings = it.settings.copy(apps = apps, onlyApps = only, perApp = true)) }; reconnect()
                    }
                }
                // «Только YouTube и Telegram»: отмеченные сервисы идут через VPN, остальное — напрямую.
                val services = cfg.services
                fun toggleService(id: String, v: Boolean) {
                    Store.update { it.copy(settings = it.settings.copy(services = if (v) (it.settings.services + id).distinct() else it.settings.services - id)) }
                    if (status.state == Tunnel.State.CONNECTED) onConnect()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Через VPN только:", fontSize = 13.sp)
                    Checkbox("youtube" in services, { toggleService("youtube", it) }); Text("YouTube", fontSize = 13.sp)
                    Checkbox("telegram" in services, { toggleService("telegram", it) }); Text("Telegram", fontSize = 13.sp)
                }
                Text(if (services.isEmpty()) "Ничего не отмечено — через VPN идёт весь трафик"
                    else "Через VPN: " + com.vlesscardvpn.core.Services.label(services) + ", остальное напрямую (другие сервисы — в Настройках)",
                    fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (mode != Mode.BYEDPI) {
            Card(Modifier.fillMaxWidth().clickable { openServers() }, shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text(when {
                        sum.selectedCount > 0 -> "Выбрано серверов: ${sum.selectedCount}"
                        mode == Mode.AUTO -> "Серверы выберу сам"
                        else -> "Серверы не выбраны"
                    }, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        sum.total == 0 -> "Откройте «Серверы» → «Подписки» или вставьте свою ссылку"
                        sum.selectedCount == 0 && sum.working > 0 -> "Рабочих по последней проверке: ${sum.working}. Подключусь к 5 самым быстрым — выбирать ничего не нужно."
                        sum.selectedCount == 0 -> "Всего ${sum.total}. Нажмите «Подключить» — проверю и выберу сам."
                        else -> sum.selectedNames.joinToString() + if (sum.selectedCount > 3) " и ещё ${sum.selectedCount - 3}" else ""
                    }, fontSize = 13.sp, color = Color.Gray)
                    if (sum.bestMs > 0) Text("Лучший: ${sum.bestMs} мс" + (if (sum.bestKbps > 0) " · ${Actions.mbps(sum.bestKbps)}" else "") +
                        when (sum.bestYt) { true -> " · YouTube ✓"; false -> " · YouTube ✗"; null -> "" },
                        fontSize = 13.sp, color = if (sum.bestKbps in 1..2999 || sum.bestYt == false) Warn else Good)
                    Text("Маскировка: авто" + (if (sum.mask.isNotEmpty()) " — ${sum.mask}" else " (подбирается сама под сеть)"), fontSize = 12.sp, color = Color.Gray,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    CountryPicker(onChange = { reconnect() })
                    if (sum.heavyMask) Text("Маскировка «тяжёлая» (мелкие пакеты) — видео может тормозить. После подключения сам поищу лёгкую", fontSize = 11.sp, color = Warn)
                }
            }
        }
        if (mode != Mode.SERVERS) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { Actions.findDpi() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Подобрать обход DPI для этой сети") }
        }
        val nowSec = System.currentTimeMillis() / 1000
        cfg.subInfo.filter { (u, i) -> u in cfg.subscriptions && i.ending(nowSec) }.forEach { (u, i) ->
            Spacer(Modifier.height(8.dp))
            Text("Подписка «${i.title.ifEmpty { com.vlesscardvpn.model.Subs.title(u) }}» заканчивается: " + Actions.subLine(i, nowSec) + ". Продлите у продавца",
                fontSize = 13.sp, color = Warn, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        var tools by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick = { tools = !tools }, modifier = Modifier.fillMaxWidth()) {
            Text(if (tools) "Скрыть инструменты ▴" else "Инструменты: скорость, сайты, сеть ▾")
        }
        if (tools) {
        Spacer(Modifier.height(12.dp))
        NetCard(on = status.state == Tunnel.State.CONNECTED, cfgKey = cfg.hashCode() + status.check.hashCode())
        Spacer(Modifier.height(12.dp))
        val report by Actions.netReport.collectAsState()
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Что с сетью?", fontWeight = FontWeight.SemiBold)
                if (report.verdict.isEmpty()) Text("Проверю, режут ли YouTube (DPI) и не включены ли белые списки", fontSize = 13.sp, color = Color.Gray)
                else {
                    Text(report.verdict, fontSize = 14.sp, color = when (report.kind) { Actions.Kind.OPEN -> Good; Actions.Kind.OFFLINE -> Bad; else -> Warn })
                    Text(report.details, fontSize = 12.sp, color = Color.Gray)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Actions.diagnoseNetwork() }, enabled = !busy) { Text("Проверить сеть") }
                    when (report.kind) {
                        Actions.Kind.WHITELIST -> Button(onClick = { Store.update { it.copy(settings = it.settings.copy(mode = Mode.SERVERS)) }; Actions.whitelistServers() },
                            enabled = !busy) { Text("Серверы для белых списков") }
                        Actions.Kind.DPI -> Button(onClick = { Actions.findDpi() }, enabled = !busy) { Text("Подобрать обход") }
                        else -> {}
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SiteCard(on = status.state == Tunnel.State.CONNECTED, reconnect = { reconnect() })
        }
        Spacer(Modifier.height(12.dp)); LiveProgress(stop = true)
    }
}

/** «Сайт не открывается?»: directly vs through the VPN vs through the DPI bypass, then route it the way that works. */
@Composable
private fun SiteCard(on: Boolean, reconnect: () -> Unit) {
    val r by Actions.siteCheck.collectAsState()
    var input by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Сайт не открывается?", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("адрес, например rutracker.org", fontSize = 13.sp) })
                OutlinedButton(onClick = { Actions.checkSite(input) }, enabled = !r.running) { Text("Проверить") }
            }
            if (r.verdict.isEmpty()) Text("Сравню: напрямую, через VPN и через обход DPI — и скажу, кто виноват" + if (!on) " (лучше с включённым VPN)" else "", fontSize = 12.sp, color = Color.Gray)
            else {
                Text(r.verdict, fontSize = 14.sp, color = when { r.running -> Color.Gray; r.good -> Good; else -> Warn })
                if (r.details.isNotEmpty()) Text(r.details, fontSize = 12.sp, color = Color.Gray)
            }
            if (!r.running && r.suggest.isNotEmpty()) Button(onClick = { Actions.routeSite(r.host, r.suggest); reconnect() }) {
                Text(if (r.suggest == "vpn") "Всегда через VPN" else "Всегда напрямую")
            }
            val pop by Actions.popular.collectAsState()
            OutlinedButton(onClick = { Actions.checkPopular() }, enabled = !pop.running) { Text("Проверить популярные (YouTube, Instagram, ChatGPT…)") }
            if (pop.summary.isNotEmpty()) Text(pop.summary, fontSize = 13.sp, color = when { pop.running -> Color.Gray; pop.rows.all { it.second.good } -> Good; else -> Warn })
            pop.rows.forEach { (title, c) ->
                Text((if (c.good) "✓ " else "✗ ") + title + " — " + c.verdict, fontSize = 12.sp, color = if (c.good) Color.Gray else Warn,
                    modifier = Modifier.clickable { Actions.siteCheck.value = c })
            }
        }
    }
}

/** «Эта сеть»: what was learned here, share it as a vcnet:// link, speed test through the VPN. */
@Composable
private fun NetCard(on: Boolean, cfgKey: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val working by Actions.running.collectAsState()
    val info = remember(cfgKey, working) { Actions.netInfo() }
    val speed by Actions.speed.collectAsState()
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Эта сеть: ${info.net}", fontWeight = FontWeight.SemiBold)
            Text(if (info.dpi.isEmpty()) "Обход DPI ещё не подобран" else "Обход DPI: ${info.dpi}" + if (info.dpiCount > 1) " (запасных: ${info.dpiCount - 1})" else "",
                fontSize = 12.sp, color = Color.Gray)
            Text(if (info.masks == 0) "Рабочие маскировки здесь ещё не найдены" else "Маскировок, прошедших здесь: ${info.masks}", fontSize = 12.sp, color = Color.Gray)
            if (speed.text.isNotEmpty()) Text(speed.text, fontSize = 13.sp, color = when { speed.running -> Color.Gray; speed.mbps >= 2 -> Good; else -> Warn })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Actions.speedTest() }, enabled = on && !speed.running) { Text("Тест скорости") }
                OutlinedButton(onClick = {
                    val link = Actions.shareNetProfile()
                    if (link == null) android.widget.Toast.makeText(ctx, "Пока нечего передать: сначала подключитесь или подберите обход", android.widget.Toast.LENGTH_SHORT).show()
                    else ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "Настройка VLESS Card для сети «${info.net.substringBefore(" · ")}» (вставьте в приложении: Серверы → Добавить):\n$link"), "Поделиться настройкой сети"))
                }) { Text("Поделиться настройкой") }
            }
            Text("Друг на том же провайдере вставит ссылку и сразу получит рабочий обход и маскировки — без серверов и ключей.", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

/** Progress that redraws only itself (6 times a second during a check), not the screen around it. */
@Composable
fun LiveProgress(stop: Boolean = false, show: (Actions.Progress) -> Boolean = { true }) {
    val p by Actions.progress.collectAsState()
    if (!(p.running || p.message.isNotEmpty()) || !show(p)) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ProgressBlock(p) }
        if (stop && p.running) TextButton(onClick = { Actions.cancel() }) { Text("Стоп") }
    }
}

@Composable
fun ProgressBlock(p: Actions.Progress) {
    Column(Modifier.fillMaxWidth()) {
        if (p.running) {
            Text("${p.title}: ${p.done}/${p.total}", fontSize = 13.sp)
            if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        } else Text(p.message, fontSize = 13.sp, color = Color.Gray)
    }
}

/** «Страна»: Auto takes servers of this country (when some work). For sites that want a certain country (ChatGPT, Spotify…). */
@Composable
private fun CountryPicker(onChange: () -> Unit) {
    val app by Store.ui.collectAsState()
    val list = remember(app) { Actions.countries(app) }
    if (list.size < 2 && app.settings.country.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
            Text("Страна: " + com.vlesscardvpn.core.Countries.title(app.settings.country) + " ▾", fontSize = 13.sp)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("🌐 Любая — самый быстрый") }, onClick = { open = false; pick("", onChange) })
            list.forEach { (c, n) -> DropdownMenuItem(text = { Text(com.vlesscardvpn.core.Countries.title(c) + " · $n") }, onClick = { open = false; pick(c, onChange) }) }
        }
    }
}

private fun pick(c: String, onChange: () -> Unit) {
    Store.update { it.copy(settings = it.settings.copy(country = c)) }
    if (Store.state.value.settings.mode == Mode.AUTO) Actions.selectBest(5, close = true)
    onChange()
}

/** Big round button: soft glow ring, power icon, gentle pulse while connecting. */
@Composable
private fun PowerButton(on: Boolean, busy: Boolean, color: Color, onClick: () -> Unit) {
    val c by androidx.compose.animation.animateColorAsState(color, androidx.compose.animation.core.tween(400), label = "c")
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "p")
    val pulse by inf.animateFloat(1f, 1.07f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "s")
    val k = if (busy) pulse else 1f
    Spacer(Modifier.height(18.dp))
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp).scale(k).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c.copy(alpha = 0.35f), Color.Transparent))))
        Box(Modifier.size(170.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c, c.copy(alpha = 0.7f)))).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Text(when { on -> "Отключить"; busy -> "Подключение…"; else -> "Подключить" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** Session: time and traffic as three tiles. */
@Composable
private fun SessionStats(min: Long, rx: Long, tx: Long, turbo: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Время" to (if (min >= 60) "${min / 60} ч ${min % 60} м" else "$min мин"), "Скачано" to Actions.bytes(rx), "Отправлено" to Actions.bytes(tx)).forEach { (t, v) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(t, fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
    if (turbo) Text("Ускорение включено", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
}
