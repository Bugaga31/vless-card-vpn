package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
    val app by Store.state.collectAsState()
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
                    label = { Text(when (m) { Mode.SERVERS -> "Серверы"; Mode.BYEDPI -> "ByeDPI"; Mode.HYBRID -> "Гибрид" }) })
            }
        }
        Text(when (mode) {
            Mode.SERVERS -> "Весь трафик через выбранные серверы (запросы распределяются между ними)"
            Mode.BYEDPI -> "Без сервера: обход DPI встроенными ByeDPI/zapret (YouTube, Discord и т.п.). Не помогает от блокировки по IP"
            Mode.HYBRID -> "YouTube/Discord/Telegram — через обход DPI, остальное — через серверы"
        }, fontSize = 13.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))

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
