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
    val app by Store.ui.collectAsState()
    val progress by Actions.progress.collectAsState()
    val mode = app.settings.mode
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("VLESS Card", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text("${BuildConfig.VERSION_NAME} · Xray · " + if (app.settings.stealthSocks) "прокси скрыт (случайный порт + пароль)" else "SOCKS 127.0.0.1:${app.settings.socksPort}",
            fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        val st = app.settings
        fun reconnect() { if (status.state == Tunnel.State.CONNECTED) onConnect() }
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
        val services = app.settings.services
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
        Spacer(Modifier.height(18.dp))

        val on = status.state == Tunnel.State.CONNECTED
        val busy = status.state == Tunnel.State.CONNECTING
        val color = when { on && status.checkOk == false -> Warn; on -> Good; busy -> Accent; else -> Color(0xFF2A2F3A) }
        Box(Modifier.size(200.dp).clip(CircleShape).background(color).clickable { if (on || busy) onDisconnect() else onConnect() },
            contentAlignment = Alignment.Center) {
            Text(when { on -> "Отключить"; busy -> "Подключение…"; else -> "Подключить" }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))
        if (status.message.isNotEmpty()) Text(status.message, color = if (status.state == Tunnel.State.ERROR) Bad else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        if (status.route.isNotEmpty()) Text(status.route, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (status.check.isNotEmpty()) Text(status.check, color = when (status.checkOk) { true -> Good; false -> Bad; null -> Color.Gray },
            fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(20.dp))

        if (mode != Mode.BYEDPI) {
            val sel = app.selected
            val working = app.servers.count { app.state(it).works }
            Card(Modifier.fillMaxWidth().clickable { openServers() }, shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (sel.isEmpty()) "Серверы не выбраны" else "Выбрано серверов: ${sel.size}", fontWeight = FontWeight.SemiBold)
                    Text(when {
                        app.servers.isEmpty() -> "Откройте «Серверы» → «Подписки» или вставьте свою ссылку"
                        sel.isEmpty() && working > 0 -> "Рабочих по последней проверке: $working. Без выбора подключусь к 5 лучшим."
                        sel.isEmpty() -> "Всего ${app.servers.size}. Нажмите «Проверить» на вкладке «Серверы»."
                        else -> sel.take(3).joinToString { it.name } + if (sel.size > 3) " и ещё ${sel.size - 3}" else ""
                    }, fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
        if (mode != Mode.SERVERS) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { Actions.findDpi() }, enabled = !progress.running, modifier = Modifier.fillMaxWidth()) { Text("Подобрать обход DPI для этой сети") }
        }
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
                    OutlinedButton(onClick = { Actions.diagnoseNetwork() }, enabled = !progress.running) { Text("Проверить сеть") }
                    when (report.kind) {
                        Actions.Kind.WHITELIST -> Button(onClick = { Store.update { it.copy(settings = it.settings.copy(mode = Mode.SERVERS)) }; Actions.whitelistServers() },
                            enabled = !progress.running) { Text("Серверы для белых списков") }
                        Actions.Kind.DPI -> Button(onClick = { Actions.findDpi() }, enabled = !progress.running) { Text("Подобрать обход") }
                        else -> {}
                    }
                }
            }
        }
        if (progress.running || progress.message.isNotEmpty()) {
            Spacer(Modifier.height(12.dp)); ProgressBlock(progress)
        }
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
