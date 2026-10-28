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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vlesscardvpn.core.ServerBrowser
import com.vlesscardvpn.core.Net
import kotlinx.coroutines.launch
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
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var addOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Server?>(null) }
    var country by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var moreOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    val search = query.trim()
    var order by rememberSaveable { mutableStateOf("auto") }
    var protocol by rememberSaveable { mutableStateOf("") }
    var service by rememberSaveable { mutableStateOf("any") }
    var showCountries by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var network by remember { mutableStateOf(Net.key(ctx)) }
    val owner=LocalLifecycleOwner.current
    val scroll=rememberLazyListState()
    val scope=rememberCoroutineScope()
    LaunchedEffect(owner) {owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while(true) {now=System.currentTimeMillis();network=Net.key(ctx);kotlinx.coroutines.delay(15_000)}
    }}

    val list by produceState(initialValue = emptyList<Server>(), app, filter, country, search, order, protocol, service, network, now) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val favorites = app.settings.favorites.toHashSet()
            app.servers.asSequence().map { it to app.state(it) }
                .filter { (server, state) -> when (filter) {
                    Filter.ALL -> true
                    Filter.WORKING -> state.works
                    Filter.SELECTED -> state.selected
                    Filter.FAV -> server.id in favorites
                } }
                .filter { (server, _) -> country.isEmpty() || com.vlesscardvpn.core.Countries.of(server.name) == country }
                .filter { (server, _) -> search.isEmpty() || server.name.contains(search, true) || server.address.contains(search, true) }
                .filter { (server,state) -> ServerBrowser.matches(server,state,protocol,service,network,now) }
                .toList()
                .map { it.first }
                .sortedWith(ServerBrowser.comparator(order,app::state,favorites))
        }
    }
    val countries by produceState(initialValue = emptyList<Pair<String, Int>>(), app.servers) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Actions.countries(app) }
    }

    LazyColumn(Modifier.fillMaxSize(), state=scroll, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${app.servers.size} в списке · ${list.size} показано", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { addOpen = true }) { Text("Добавить") }
            }
        }
        item {
            Button(onClick = { Actions.testAll(onlySelected = filter == Filter.SELECTED) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                Text(if (filter == Filter.SELECTED) "Проверить выбранные" else "Проверить все серверы")
            }
            Text("Проверю доступность всего списка и до 150 лучших через Xray. Подбор маскировки запускается отдельно.",
                modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { Actions.refreshSubscriptions() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Подписки") }
                OutlinedButton(onClick = { Actions.setupWarp() }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("WARP") }
                Box {
                    IconButton(onClick = { moreOpen = true }, enabled = !busy) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.MoreVert, contentDescription = "Дополнительные действия")
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text("Авто-маскировка") }, enabled = !busy, onClick = {
                            moreOpen = false
                            val target = app.selected.ifEmpty { app.servers.filter { app.state(it).tcpMs > 0 }.sortedBy { app.state(it).tcpMs }.take(10) }
                            if (target.isEmpty()) Toast.makeText(ctx, "Сначала проверьте серверы", Toast.LENGTH_SHORT).show() else Actions.findMasks(target)
                        })
                        DropdownMenuItem(text = { Text("Выбрать 5 лучших") }, enabled = !busy, onClick = {
                            moreOpen = false
                            val n = Actions.selectBest(5)
                            Toast.makeText(ctx, if (n == 0) "Сначала проверьте серверы" else "Выбрано: $n", Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text("Удалить недоступные по TCP") }, enabled = !busy, onClick = {
                            moreOpen = false
                            val now = System.currentTimeMillis()
                            val dead = app.servers.filter { server ->
                                val state = app.state(server)
                                !state.selected && com.vlesscardvpn.core.ManualServerCheckPolicy.confirmedTcpFailure(server, state, now)
                            }.map { it.id }.toSet()
                            if (dead.isEmpty()) Toast.makeText(ctx, "Недавних подтверждённых TCP-сбоев нет", Toast.LENGTH_SHORT).show()
                            else pendingDelete = dead
                        })
                    }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filter.values().forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) }) }
            }
        }
        if (countries.isNotEmpty()) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = country.isEmpty(), onClick = { country = "" }, label = { Text("🌐 Все страны") })
                countries.take(if(showCountries) countries.size else 20).forEach { (c, n) ->
                    FilterChip(selected = country == c, onClick = { country = if (country == c) "" else c }, label = { Text("${com.vlesscardvpn.core.Countries.flag(c)} $c · $n") })
                }
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Поиск по имени или адресу") },
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = null) })
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ServerBrowser.sorts.forEach { (id,label)->FilterChip(selected=order==id,onClick={order=id},label={Text(label)}) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected=protocol.isEmpty(),onClick={protocol=""},label={Text("Все протоколы")})
                    app.servers.map{it.protocol}.distinct().sorted().forEach {id->FilterChip(selected=protocol==id,onClick={protocol=if(protocol==id) "" else id},label={Text(id.uppercase())})}
                }
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ServerBrowser.services.forEach { (id,label)->FilterChip(selected=service==id,onClick={service=id},label={Text(label)}) }
                }
                Text("Фильтры меняют только список. Свежий ответ — до 5 мин на этой сети и маске, не проверка аккаунта. Сортировка скорости использует последний замер, не обещание текущей скорости.",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick={filter=Filter.ALL;country="";query="";protocol="";service="any";order="auto"}) {Text("Сбросить всё")}
                    if(countries.size>20) TextButton(onClick={showCountries=!showCountries}) {Text(if(showCountries) "Меньше стран" else "Все страны")}
                    TextButton(onClick={scope.launch{if(app.settings.reducedMotion) scroll.scrollToItem(0) else scroll.animateScrollToItem(0)}}) {Text("В начало")}
                }
            }
        }
        item { LiveProgress(stop = true) }
        if (app.settings.mode == com.vlesscardvpn.model.Mode.AUTO && app.servers.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Автоматический выбор", fontWeight = FontWeight.SemiBold)
                    Text("При подключении возьму быстрые рабочие серверы и подберу маскировку. Отмечайте серверы, только если нужны конкретные.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (app.servers.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Список пуст", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Нажмите «Подписки», чтобы загрузить бесплатные серверы, или «Добавить» и вставьте свои ссылки VLESS, VMess, Trojan, Shadowsocks или Hysteria2.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Публичные серверы контролируют посторонние люди. Не входите через них в банк и важные аккаунты; трафик без HTTPS может быть виден владельцу.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (list.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Измените поиск или сбросьте фильтры.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { filter = Filter.ALL; country = ""; query = ""; protocol="";service="any";order="auto" }) { Text("Сбросить фильтры") }
                }
            }
        }
        itemsIndexed(list, key = { _,server -> server.id }) { index,server ->
            ServerRow(server, app.state(server), fav = server.id in app.settings.favorites, compact=app.settings.compactServers, hidden=app.settings.hideServerNames, displayIndex=index+1, now=now,
                onToggle = { Store.setState(server.id) { state -> state.copy(selected = !state.selected) } },
                onLong = { menuFor = server })
        }
    }

    if (addOpen) AddDialog(onDismiss = { addOpen = false }, onAdd = { text ->
        addOpen = false
        Toast.makeText(ctx, Actions.importAny(text), Toast.LENGTH_LONG).show()
    })
    menuFor?.let { server -> ServerMenu(server, app.state(server), onDismiss = { menuFor = null }) }
    if (pendingDelete.isNotEmpty()) AlertDialog(
        onDismissRequest = { pendingDelete = emptySet() },
        title = { Text("Удалить серверы?") },
        text = { Text("Удалить ${pendingDelete.size} серверов с подтверждённым TCP-сбоем за последние сутки? Непроверенные, устаревшие и прошедшие TCP узлы сохраняются.") },
        confirmButton = { TextButton(onClick = { val count = pendingDelete.size; Store.remove(pendingDelete); pendingDelete = emptySet(); Toast.makeText(ctx, "Удалено: $count", Toast.LENGTH_SHORT).show() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { pendingDelete = emptySet() }) { Text("Отмена") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(s: Server, st: ServerState, fav: Boolean = false, compact:Boolean=false, hidden:Boolean=false, displayIndex:Int=0, now:Long=System.currentTimeMillis(), onToggle: () -> Unit, onLong: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).combinedClickable(onClick = onToggle, onLongClick = onLong),
        colors = CardDefaults.cardColors(containerColor = if (st.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = st.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text((if (fav) "★ " else "") + (if(hidden) "Сервер $displayIndex" else s.name), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                val mask = Masks.byId(st.maskId)?.title
                if(s.insecure) Text("TLS: проверка сертификата отключена",style=MaterialTheme.typography.labelSmall,color=Bad)
                if(!compact) Text(ServerBrowser.age(st.checkedAt,now),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                // Catalog subscriptions are third-party free servers: mark them so they are not mistaken for your own.
                val isPublic = com.vlesscardvpn.model.Subs.find(s.source) != null
                if(!compact) Text(s.label + (if (isPublic) " · публичный" else "") + (mask?.let { " · $it" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                val real = st.realMs
                Text(when { real > 0 -> "$real мс"; real == 0 -> "не работает"; st.tcpMs == 0 -> "недоступен"; st.tcpMs > 0 -> "TCP ${st.tcpMs} мс"; else -> "—" },
                    color = when { real > 0 && st.bigOk != false -> Good; real > 0 -> Warn; real == 0 || st.tcpMs == 0 -> Bad; else -> MaterialTheme.colorScheme.onSurfaceVariant }, fontSize = 13.sp)
                if (real > 0 && !compact) Text(buildString {
                    append(when (st.bigOk) { true -> if (st.kbps > 0) Actions.mbps(st.kbps) else "256К ✓"; false -> "256К ✗ (обрыв)"; null -> "" })
                    st.ytOk?.let { append(if (it) " · YT ✓" else " · YT ✗") }
                    st.tgOk?.let { append(if (it) " · TG ✓" else " · TG ✗") }
                }, fontSize = 11.sp, color = if (st.bigOk == false) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Ссылку на подписку (https://…) добавьте в Настройках → Подписки.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
private fun ServerMenu(s: Server, st: ServerState, onDismiss: () -> Unit) {
    val currentSettings by Store.settings.collectAsState()
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
                    item { TextButton(onClick = { Store.setState(s.id) { com.vlesscardvpn.core.MaskSamplePolicy.invalidate(it).copy(maskId = "", netMasks = emptyMap()) }; onDismiss() }) { Text("Без маскировки (как в ссылке)") } }
                    items(options) { m -> TextButton(onClick = { Store.setState(s.id) { com.vlesscardvpn.core.MaskSamplePolicy.invalidate(it).copy(maskId = m.id, netMasks = emptyMap()) }; onDismiss() }) {
                        Text((if (m.id == st.maskId) "● " else "") + m.title, fontSize = 13.sp) } }
                }
            }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(s.name, maxLines = 2) },
        text = {
            Column {
                Text("${s.label}\n${s.address}:${s.port}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Маскировка: " + (Masks.byId(st.maskId)?.title ?: "как в ссылке") + " — стоимость ${Masks.byId(st.maskId)?.let { Masks.cost(it) } ?: 0} (эвристика нагрузки, не невидимость). Пригодность решают реальные пробы этой сети.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val fav = s.id in currentSettings.favorites
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
                Text("Внутри ключ сервера — показывайте только себе и тем, кому доверяете.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

fun qrBitmap(text: String, size: Int): android.graphics.Bitmap? = runCatching {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 2, com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
}.getOrNull()
