package com.vlesscardvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.xray.Masks

private enum class Filter(val title: String) { ALL("Все"), WORKING("Рабочие"), SELECTED("Выбранные"), FAV("★ Избранные") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen() {
    val ctx = LocalContext.current
    val app by Store.ui.collectAsState()
    val busy by Actions.running.collectAsState()
    var filter by remember { mutableStateOf(Filter.ALL) }
    var addOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Server?>(null) }
    var country by remember { mutableStateOf("") }

    // Sorting thousands of servers happens off the main thread; the old list stays on screen meanwhile.
    val list by produceState(initialValue = emptyList<Server>(), app, filter, country) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val rank = { s: Server -> val st = app.state(s)
            when { st.selected -> 0; st.works -> 1; st.tcpMs > 0 && st.realMs < 0 -> 2; st.realMs < 0 && st.tcpMs < 0 -> 3; else -> 4 } }
        val favs = app.settings.favorites.toHashSet()
        app.servers.filter { when (filter) { Filter.ALL -> true; Filter.WORKING -> app.state(it).works; Filter.SELECTED -> app.state(it).selected; Filter.FAV -> it.id in favs } }
            .filter { country.isEmpty() || com.vlesscardvpn.core.Countries.of(it.name) == country }
            .sortedWith(compareBy<Server>(rank).thenBy { app.state(it).let { st -> if (st.works) st.score else if (st.realMs > 0) st.realMs + 5000 else Int.MAX_VALUE } }
                .thenBy { app.state(it).tcpMs.let { ms -> if (ms > 0) ms else Int.MAX_VALUE } })
    } }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { addOpen = true }) { Text("Добавить") }
            OutlinedButton(onClick = { Actions.refreshSubscriptions() }, enabled = !busy) { Text("Подписки") }
            OutlinedButton(onClick = { Actions.setupWarp() }, enabled = !busy) { Text("WARP") }
            OutlinedButton(onClick = { Actions.testAll(onlySelected = filter == Filter.SELECTED) }, enabled = !busy) { Text("Проверить") }
            OutlinedButton(onClick = {
                val target = app.selected.ifEmpty { app.servers.filter { app.state(it).tcpMs > 0 }.sortedBy { app.state(it).tcpMs }.take(10) }
                Actions.findMasks(target)
            }, enabled = !busy) { Text("Авто-маскировка") }
            OutlinedButton(onClick = {
                val n = Actions.selectBest(5); Toast.makeText(ctx, if (n == 0) "Сначала нажмите «Проверить»" else "Выбрано: $n", Toast.LENGTH_SHORT).show()
            }) { Text("5 лучших") }
            OutlinedButton(onClick = {
                val dead = app.servers.filter { val st = app.state(it); !st.selected && (st.tcpMs == 0 || st.realMs == 0) }.map { it.id }.toSet()
                Store.remove(dead); Toast.makeText(ctx, "Удалено: ${dead.size}", Toast.LENGTH_SHORT).show()
            }, enabled = !busy) { Text("Удалить нерабочие") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.values().forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) }) }
            Text("${list.size}", color = Color.Gray, fontSize = 12.sp)
        }
        val countries = remember(app) { Actions.countries(app) }
        if (countries.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = country.isEmpty(), onClick = { country = "" }, label = { Text("🌐 Все страны") })
            countries.take(20).forEach { (c, n) ->
                FilterChip(selected = country == c, onClick = { country = if (country == c) "" else c }, label = { Text("${com.vlesscardvpn.core.Countries.flag(c)} $c · $n") })
            }
        }
        LiveProgress(stop = true)
        if (app.settings.mode == com.vlesscardvpn.model.Mode.AUTO && app.servers.isNotEmpty())
            Text("Режим «Авто»: выбирать ничего не нужно — при подключении возьму 5 самых быстрых рабочих и сам подберу маскировку. " +
                "Отмечайте серверы, только если нужны конкретные.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp))
        if (app.servers.isEmpty()) {
            Text("Список пуст. Нажмите «Подписки» — загрузятся бесплатные серверы для России (igareck, с зеркал), " +
                "или «Добавить» и вставьте свои ссылки vless:// vmess:// trojan:// ss:// hysteria2://.\n\n" +
                "Публичные серверы держат посторонние люди: владелец видит, куда вы ходите, и может читать трафик без HTTPS. " +
                "Не входите через них в банк и важные аккаунты.", color = Color.Gray, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(list, key = { it.id }) { s ->
                ServerRow(s, app.state(s), fav = s.id in app.settings.favorites, onToggle = { Store.setState(s.id) { st -> st.copy(selected = !st.selected) } }, onLong = { menuFor = s })
            }
        }
    }

    if (addOpen) AddDialog(onDismiss = { addOpen = false }, onAdd = { text ->
        addOpen = false
        Toast.makeText(ctx, Actions.importAny(text), Toast.LENGTH_LONG).show()
    })
    menuFor?.let { s -> ServerMenu(s, app.state(s), onDismiss = { menuFor = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(s: Server, st: ServerState, fav: Boolean = false, onToggle: () -> Unit, onLong: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).combinedClickable(onClick = onToggle, onLongClick = onLong),
        colors = CardDefaults.cardColors(containerColor = if (st.selected) Color(0xFF1C2A44) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = st.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text((if (fav) "★ " else "") + s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                val mask = Masks.byId(st.maskId)?.title
                // Catalog subscriptions are third-party free servers: mark them so they are not mistaken for your own.
                val isPublic = com.vlesscardvpn.model.Subs.find(s.source) != null
                Text(s.label + (if (isPublic) " · публичный" else "") + (mask?.let { " · $it" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                val real = st.realMs
                Text(when { real > 0 -> "$real мс"; real == 0 -> "не работает"; st.tcpMs == 0 -> "недоступен"; st.tcpMs > 0 -> "TCP ${st.tcpMs} мс"; else -> "—" },
                    color = when { real > 0 && st.bigOk != false -> Good; real > 0 -> Warn; real == 0 || st.tcpMs == 0 -> Bad; else -> Color.Gray }, fontSize = 13.sp)
                if (real > 0) Text(buildString {
                    append(when (st.bigOk) { true -> if (st.kbps > 0) Actions.mbps(st.kbps) else "256К ✓"; false -> "256К ✗ (обрыв)"; null -> "" })
                    st.ytOk?.let { append(if (it) " · YT ✓" else " · YT ✗") }
                    st.tgOk?.let { append(if (it) " · TG ✓" else " · TG ✗") }
                }, fontSize = 11.sp, color = if (st.bigOk == false) Warn else Color.Gray)
            }
        }
    }
}

@Composable
private fun AddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Добавить серверы") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                    placeholder = { Text("Ссылки vless:// vmess:// trojan:// ss:// hysteria2:// wireguard:// socks://, WireGuard .conf (WARP), Xray JSON, текст подписки, маскировки vcmask://, настройка сети vcnet:// или резервная копия vcbackup://") })
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                }) { Text("Вставить из буфера") }
                Text("Ссылку на подписку (https://…) добавьте в Настройках → Подписки.", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = { Button(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun ServerMenu(s: Server, st: ServerState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var masks by remember { mutableStateOf(false) }
    var fork by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    if (qr) { QrDialog(s.name, LinkParser.toLink(s), onDismiss = { qr = false; onDismiss() }); return }
    if (fork) { MaskEditor(Masks.byId(st.maskId) ?: Masks.DEFAULT, onDismiss = { fork = false; onDismiss() }); return }
    if (masks) {
        val options = Masks.searchOrder(true, s)
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Маскировка: ${options.size} вариантов") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { TextButton(onClick = { Store.setState(s.id) { it.copy(maskId = "", netMasks = emptyMap()) }; onDismiss() }) { Text("Без маскировки (как в ссылке)") } }
                    items(options) { m -> TextButton(onClick = { Store.setState(s.id) { it.copy(maskId = m.id, netMasks = emptyMap()) }; onDismiss() }) {
                        Text((if (m.id == st.maskId) "● " else "") + m.title, fontSize = 13.sp) } }
                }
            }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(s.name, maxLines = 2) },
        text = {
            Column {
                Text("${s.label}\n${s.address}:${s.port}", fontSize = 13.sp, color = Color.Gray)
                Text("Маскировка: " + (Masks.byId(st.maskId)?.title ?: "авто") + " — подбирается сама под сеть и меняется, если её распознают.", fontSize = 12.sp, color = Color.Gray)
                TextButton(onClick = { Actions.findMasks(listOf(s)); onDismiss() }) { Text("Подобрать заново для этой сети") }
                var expert by remember { mutableStateOf(false) }
                TextButton(onClick = { expert = !expert }) { Text(if (expert) "Для опытных ▾" else "Для опытных ▸") }
                if (expert) {
                    TextButton(onClick = { masks = true }) { Text("Выбрать маскировку вручную") }
                    TextButton(onClick = { fork = true }) { Text("Сделать свою на основе текущей") }
                }
                Masks.byId(st.maskId)?.takeIf { it.custom || it.auto }?.let { m -> TextButton(onClick = { shareMyMask(ctx, m); onDismiss() }) { Text("Поделиться маскировкой") } }
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", LinkParser.toLink(s)))
                    Toast.makeText(ctx, "Ссылка скопирована", Toast.LENGTH_SHORT).show(); onDismiss()
                }) { Text("Копировать ссылку") }
                val fav = s.id in Store.state.value.settings.favorites
                TextButton(onClick = { Actions.toggleFavorite(s.id); onDismiss() }) {
                    Text(if (fav) "Убрать из избранного" else "★ В избранное (Авто всегда берёт его, если работает)")
                }
                TextButton(onClick = { qr = true }) { Text("QR-код (отсканировать другим телефоном)") }
                TextButton(onClick = {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, LinkParser.toLink(s)), "Поделиться сервером"))
                    onDismiss()
                }) { Text("Поделиться") }
                TextButton(onClick = { Store.remove(setOf(s.id)); onDismiss() }) { Text("Удалить", color = Bad) }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

/** QR of a link: scan it with the camera / another VPN app on the other phone. Contains the server key — show only to your own. */
@Composable
fun QrDialog(title: String, text: String, onDismiss: () -> Unit) {
    val bmp = remember(text) { qrBitmap(text, 720) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title, maxLines = 2) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), "QR", Modifier.size(280.dp))
                else Text("Ссылка слишком длинная для QR-кода")
                Text("Внутри ключ сервера — показывайте только себе и тем, кому доверяете.", fontSize = 12.sp, color = Color.Gray)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

fun qrBitmap(text: String, size: Int): android.graphics.Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
}.getOrNull()
