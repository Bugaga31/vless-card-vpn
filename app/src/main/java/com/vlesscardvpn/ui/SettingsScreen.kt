package com.vlesscardvpn.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.ByeDpiArgs
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.XrayCore
import com.vlesscardvpn.model.Balance
import com.vlesscardvpn.model.Settings

@Composable
fun SettingsScreen() {
    val app by Store.state.collectAsState()
    val s = app.settings
    val progress by Actions.progress.collectAsState()
    fun set(f: (Settings) -> Settings) = Store.update { it.copy(settings = f(it.settings)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Несколько серверов одновременно")
        Text("Запросы распределяются между всеми отмеченными серверами; неработающие автоматически исключаются (проверка раз в минуту).", fontSize = 12.sp, color = Color.Gray)
        Balance.values().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.balance == b, onClick = { set { it.copy(balance = b) } }); Text(b.title)
            }
        }
        Section("Маршрутизация")
        Toggle("Российские сайты напрямую (банки, Госуслуги, VK, Яндекс)", s.ruDirect) { v -> set { it.copy(ruDirect = v) } }
        Toggle("Блокировать рекламу", s.blockAds) { v -> set { it.copy(blockAds = v) } }
        var port by remember(s.socksPort) { mutableStateOf(s.socksPort.toString()) }
        OutlinedTextField(port, { v -> port = v.filter { it.isDigit() }.take(5); port.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { p -> set { it.copy(socksPort = p) } } },
            label = { Text("Локальный SOCKS-порт (127.0.0.1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        var hybrid by remember(s.hybridDomains) { mutableStateOf(s.hybridDomains.joinToString(", ")) }
        OutlinedTextField(hybrid, { v -> hybrid = v; set { it.copy(hybridDomains = v.split(',', ' ', '\n').map { d -> d.trim() }.filter { d -> d.isNotEmpty() }) } },
            label = { Text("Гибрид: через ByeDPI (geosite:… или домены)") }, modifier = Modifier.fillMaxWidth())

        Section("ByeDPI")
        var args by remember(s.byeDpiArgs) { mutableStateOf(s.byeDpiArgs) }
        val parsed = ByeDpiArgs.parse(args, s.byeDpiSni)
        OutlinedTextField(args, { v -> args = v; if (ByeDpiArgs.parse(v, s.byeDpiSni).isSuccess) set { it.copy(byeDpiArgs = v) } },
            label = { Text("Стратегия (как в ByeByeDPI)") }, modifier = Modifier.fillMaxWidth(),
            isError = parsed.isFailure, supportingText = { parsed.exceptionOrNull()?.message?.let { Text(it) } })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(Settings.DEFAULT_BYEDPI) + ByeDpiArgs.EXAMPLES).distinct().forEach { e ->
                AssistChip(onClick = { args = e; set { it.copy(byeDpiArgs = e) } }, label = { Text(e, fontSize = 11.sp) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ByeDpiArgs.MASK_DOMAINS.forEach { d -> FilterChip(selected = s.byeDpiSni == d, onClick = { set { it.copy(byeDpiSni = d) } }, label = { Text(d) }) }
        }
        OutlinedButton(onClick = { Actions.testByeDpi() }, enabled = !progress.running) { Text("Проверить ByeDPI") }
        if (progress.title == "Проверка ByeDPI") ProgressBlock(progress)

        Section("Подписки")
        var subs by remember(s.subscriptions) { mutableStateOf(s.subscriptions.joinToString("\n")) }
        OutlinedTextField(subs, { v -> subs = v; set { it.copy(subscriptions = v.lines().map { l -> l.trim() }.filter { l -> l.startsWith("http") }) } },
            label = { Text("Ссылки подписок, по одной в строке") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        TextButton(onClick = { subs = Settings.DEFAULT_SUBSCRIPTIONS.joinToString("\n"); set { it.copy(subscriptions = Settings.DEFAULT_SUBSCRIPTIONS) } }) { Text("Вернуть стандартные") }

        Section("Проверка")
        var url by remember(s.testUrl) { mutableStateOf(s.testUrl) }
        OutlinedTextField(url, { v -> url = v; if (v.startsWith("https://")) set { it.copy(testUrl = v) } }, label = { Text("Адрес для пинга") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(XrayCore.version(), fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable private fun Section(t: String) = Text(t, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))

@Composable private fun Toggle(t: String, v: Boolean, on: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { Text(t, Modifier.weight(1f), fontSize = 14.sp); Switch(v, on) }
