package com.vlesscardvpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.AppWatch
import com.vlesscardvpn.core.Assistant
import com.vlesscardvpn.core.GpsMock
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.Tunnel

private val Bubble = Color(0xFF1F232C)
private val Glow = Brush.linearGradient(listOf(Color(0xFF6A5CFF), Accent, Color(0xFF2EC2B5)))

/** «Помощник»: live panel (status, autopilot switches, what it did) + chat with action buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val msgs by Assistant.messages.collectAsState()
    val cfg by Store.settings.collectAsState()
    val sum by Store.summary.collectAsState()
    val status by Tunnel.status.collectAsState()
    val journal by AppWatch.journal.collectAsState()
    val prog by Actions.progress.collectAsState()
    var input by remember { mutableStateOf("") }
    var panel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    fun send(t: String = input) { if (t === input) input = ""; Assistant.send(ctx, t) }
    val on = status.state == Tunnel.State.CONNECTED

    Column(Modifier.fillMaxSize().imePadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Glow), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Psychology, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Помощник", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (cfg.autopilot && on) Good else Color.Gray))
                    Spacer(Modifier.width(6.dp))
                    Text(when { prog.running -> "Работаю: ${prog.title}"; cfg.autopilot && on -> "Автопилот следит за связью"; cfg.autopilot -> "Автопилот ждёт подключения"; else -> "Автопилот выключен" },
                        fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = { panel = !panel }) { Text(if (panel) "Скрыть" else "Панель") }
        }
        AnimatedVisibility(panel) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, Brush.linearGradient(listOf(Color(0x556A5CFF), Color(0x332EC2B5))), RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Связь", if (on) (if (status.checkOk == false) "Нет интернета" else "Подключено") else "Выключено",
                        if (on) (if (status.checkOk == false) Warn else Good) else Color.Gray, Modifier.weight(1f))
                    Tile("Маска", sum.mask.ifEmpty { "подберу сам" }, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    Tile("Лучший", if (sum.bestMs > 0) "${sum.bestMs} мс" + (if (sum.bestKbps > 0) " · " + Actions.mbps(sum.bestKbps) else "") else "—",
                        MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fun set(f: (com.vlesscardvpn.model.Settings) -> com.vlesscardvpn.model.Settings) = Store.update { it.copy(settings = f(it.settings)) }
                    FilterChip(cfg.autopilot, { set { it.copy(autopilot = !it.autopilot) } }, label = { Text("🧭 Автопилот") })
                    FilterChip(cfg.appAware, { set { it.copy(appAware = !it.appAware) }; if (!cfg.appAware && !AppWatch.hasAccess(ctx)) AppWatch.openAccess(ctx) }, label = { Text("📱 Под приложение") })
                    FilterChip(cfg.gpsSpoof, { set { it.copy(gpsSpoof = !it.gpsSpoof) }; if (cfg.gpsSpoof) GpsMock.stop(ctx) }, label = { Text("📍 GPS") })
                    FilterChip(cfg.autoCool, { set { it.copy(autoCool = !it.autoCool) } }, label = { Text(if (com.vlesscardvpn.core.Thermal.cooling) "❄ Охлаждаю" else "❄ Охлаждение") })
                    FilterChip(cfg.smartYoutube, { set { it.copy(smartYoutube = !it.smartYoutube, ytDpi = emptyMap()) } }, label = { Text("▶ Умный YouTube") })
                }
                if (journal.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Что я сделал сам", fontSize = 12.sp, color = Color.Gray)
                    journal.take(3).forEach { (t, s) ->
                        Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) + "  " + s.removePrefix("Автопилот: "),
                            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
                    if (!m.mine) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Glow), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Psychology, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.widthIn(max = 300.dp), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                        Box(Modifier.clip(if (m.mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                            .background(if (m.mine) Brush.linearGradient(listOf(Accent, Color(0xFF6A5CFF))) else Brush.linearGradient(listOf(Bubble, Bubble)))
                            .padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(m.text, fontSize = 14.sp, color = Color.White, lineHeight = 19.sp)
                        }
                        if (m.buttons.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            m.buttons.forEach { b ->
                                FilledTonalButton(onClick = { Assistant.run(ctx, b.cmd) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.heightIn(min = 32.dp)) { Text(b.label, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
            if (prog.running) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Glow))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)).background(Bubble).padding(12.dp)) {
                        Text(prog.title + if (prog.total > 0) " · ${prog.done}/${prog.total}" else "…", fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        if (prog.total > 0) LinearProgressIndicator(progress = { prog.done.toFloat() / prog.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { Actions.cancel() }, contentPadding = PaddingValues(0.dp)) { Text("Стоп", fontSize = 12.sp) }
                    }
                }
            }
        }
        if (input.isEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("⚡ Улучши соединение", "▶ Ускорь YouTube", "🧬 Подбери маску", "🔁 Смени маску", "🌐 Что с сетью?", "📱 Проверь телефон", "🚀 Ускорь телефон", "❄ Охлади").forEach { q ->
                SuggestionChip(onClick = { send(q.substringAfter(' ')) }, label = { Text(q, fontSize = 12.sp) }, shape = RoundedCornerShape(16.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                placeholder = { Text("Напишите, что сделать…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) send() }))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (input.isNotBlank()) Glow else Brush.linearGradient(listOf(Bubble, Bubble)))
                .then(if (input.isNotBlank()) Modifier.clickableNoRipple { send() } else Modifier), contentAlignment = Alignment.Center) {
                Text("➤", color = Color.White, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF12151B)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(title, fontSize = 11.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit) = this.then(Modifier.clickable(onClick = onClick))
