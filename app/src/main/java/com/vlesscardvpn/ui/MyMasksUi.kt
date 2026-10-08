package com.vlesscardvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.MyMasks
import org.json.JSONObject

/** Short human description of what a mask does (for the list and the editor preview). */
fun describeMask(m: Mask): String = buildList {
    add(m.fingerprint)
    when {
        m.lengths.isNotEmpty() -> add("лесенка ${m.lengths.split(',').size} ступ.")
        m.packets.isNotEmpty() -> add("дробление ${m.packets} по ${m.length} байт")
    }
    if (m.noise.isNotEmpty()) add((Masks.NOISES + Masks.WG_NOISES).firstOrNull { it[0] == m.noise }?.get(1) ?: m.noise)
    if (m.noiseJson.isNotEmpty()) add("свой шум")
    if (m.hop == Masks.HOP_LOCAL) add("смена порта") else if (m.hop == Masks.HOP_WARP) add("прыжки по портам WARP")
    if (m.dpi == Masks.CURRENT_DPI) add("+ обход DPI")
}.joinToString(" · ")

fun saveMyMask(m: Mask, replacing: String? = null) = Store.update { st ->
    val list = MyMasks.load(st.settings.myMasks).filter { it.id != replacing && it.id != m.id } + m
    st.copy(settings = st.settings.copy(myMasks = list.map { MyMasks.store(it) }))
}

fun shareMyMask(ctx: Context, m: Mask) {
    val link = MyMasks.encode(m)
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("mask", link))
    Toast.makeText(ctx, "Ссылка на маскировку скопирована", Toast.LENGTH_SHORT).show()
    runCatching {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Маскировка для VLESS Card «${m.title}»: $link"), "Поделиться маскировкой").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Settings → «Мои маскировки»: own masks, made here or received as vcmask:// links. */
@Composable
fun MyMasksSection() {
    val ctx = LocalContext.current
    val cfg by Store.settings.collectAsState()
    val mine = remember(cfg.myMasks) { MyMasks.load(cfg.myMasks) }
    var edit by remember { mutableStateOf<Mask?>(null) }
    var creating by remember { mutableStateOf(false) }
    Text("Мои маскировки", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
    Text("Соберите свою маскировку или вставьте ссылку vcmask:// от друга. Свои пробуются первыми при подборе и видны в «Выбрать маскировку вручную». " +
        "В ссылке только параметры маскировки — ни серверов, ни ключей.", fontSize = 12.sp, color = Color.Gray)
    mine.forEach { m ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(m.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(describeMask(m), fontSize = 11.sp, color = Color.Gray)
                Row {
                    TextButton(onClick = { shareMyMask(ctx, m) }) { Text("Поделиться") }
                    TextButton(onClick = { edit = m }) { Text("Изменить") }
                    TextButton(onClick = { Store.update { st -> st.copy(settings = st.settings.copy(myMasks = mine.filter { it.id != m.id }.map { MyMasks.store(it) })) } }) { Text("Удалить", color = Bad) }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { creating = true }) { Text("Создать") }
        OutlinedButton(onClick = {
            val text = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            val n = Actions.importMasks(text)
            val np = Actions.importNetProfile(text)
            if (np != null) Toast.makeText(ctx, np, Toast.LENGTH_LONG).show() else Toast.makeText(ctx, when {
                n > 0 -> "Добавлено маскировок: $n"
                MyMasks.parseLinks(text).isNotEmpty() -> "Эти маскировки уже есть"
                else -> "В буфере нет ссылки vcmask://"
            }, Toast.LENGTH_SHORT).show()
        }) { Text("Вставить ссылку") }
    }
    if (creating || edit != null) MaskEditor(edit, onDismiss = { creating = false; edit = null })
    MaskEvolutionSection()
}

/** «Эволюция масок»: auto masks bred from what passed (MaskLab); the best can be kept as own ones or shared. */
@Composable
fun MaskEvolutionSection() {
    val ctx = LocalContext.current
    val cfg by Store.settings.collectAsState()
    val auto = remember(cfg.autoMasks) { com.vlesscardvpn.xray.MaskLab.load(cfg.autoMasks) }
    var all by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Эволюция масок", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("При каждом подборе пробуются мутанты масок, которые прошли в этой сети: другие размеры кусков, паузы, ступеньки, шум. " +
                "Прошедшие остаются и дают следующее поколение — маски уходят от шаблонов, которые выучил ТСПУ.", fontSize = 12.sp, color = Color.Gray)
        }
        Switch(cfg.maskEvolution, { v -> Store.update { st -> st.copy(settings = st.settings.copy(maskEvolution = v)) } })
    }
    if (auto.isEmpty()) { Text("Авто-масок пока нет — появятся после «Подобрать маскировку».", fontSize = 12.sp, color = Color.Gray); return }
    Text("Выведено авто-масок: ${auto.size}", fontSize = 12.sp)
    (if (all) auto else auto.take(5)).forEach { m ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text(m.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(describeMask(m), fontSize = 11.sp, color = Color.Gray)
                Row {
                    TextButton(onClick = { saveMyMask(MyMasks.fork(m, m.title.replace("Авто:", "Моя:"))); Toast.makeText(ctx, "Добавлено в «Мои маскировки»", Toast.LENGTH_SHORT).show() }) { Text("В мои") }
                    TextButton(onClick = { shareMyMask(ctx, m) }) { Text("Поделиться") }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (auto.size > 5) OutlinedButton(onClick = { all = !all }) { Text(if (all) "Свернуть" else "Показать все") }
        OutlinedButton(onClick = { Store.update { st -> st.copy(settings = st.settings.copy(autoMasks = emptyList())) } }) { Text("Очистить", color = Bad) }
    }
}

private enum class Kind(val title: String) { FRAG("Дробление (TCP)"), LADDER("Лесенка (TCP)"), NOISE("Шум (UDP, WireGuard)"), PLAIN("Только отпечаток") }

/** Editor of an own mask. [base] = mask being edited (or a built-in to start from); null = new. */
@Composable
fun MaskEditor(base: Mask?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val b = base ?: Masks.byId("chrome.l2")!!.copy(title = "")
    var title by remember { mutableStateOf(if (base?.custom == true) base.title else base?.title?.let { "$it (моя)" } ?: "") }
    var kind by remember { mutableStateOf(when { b.hasNoise || b.hop.isNotEmpty() -> Kind.NOISE; b.lengths.isNotEmpty() -> Kind.LADDER; b.packets.isNotEmpty() -> Kind.FRAG; else -> Kind.PLAIN }) }
    var fp by remember { mutableStateOf(b.fingerprint.ifEmpty { "chrome" }) }
    var packets by remember { mutableStateOf(b.packets.ifEmpty { "tlshello" }) }
    var length by remember { mutableStateOf(b.length.ifEmpty { "10-30" }) }
    var delay by remember { mutableStateOf(b.delay.ifEmpty { "5-10" }) }
    var maxSplit by remember { mutableStateOf(b.maxSplit.takeIf { it != "0" }.orEmpty()) }
    var lengths by remember { mutableStateOf(b.lengths.ifEmpty { "1-1,2-4,5-10,20-60" }) }
    var delays by remember { mutableStateOf(b.delays.ifEmpty { "1-2,2-4,3-6,5-10" }) }
    var noise by remember { mutableStateOf(b.noise.ifEmpty { if (b.noiseJson.isEmpty()) "z5" else "" }) }
    var noiseJson by remember { mutableStateOf(b.noiseJson) }
    var hop by remember { mutableStateOf(b.hop) }
    var viaDpi by remember { mutableStateOf(b.dpi == Masks.CURRENT_DPI) }
    var error by remember { mutableStateOf("") }

    fun build(): Mask = MyMasks.fromJson(JSONObject().put("t", title).put("fp", fp).apply {
        when (kind) {
            Kind.FRAG -> put("p", packets).put("l", length).put("d", delay).put("ms", maxSplit)
            Kind.LADDER -> put("p", packets).put("ls", lengths).put("ds", delays).put("ms", maxSplit)
            Kind.NOISE -> { if (noiseJson.isNotBlank()) put("nj", noiseJson) else put("n", noise); put("h", hop) }
            Kind.PLAIN -> {}
        }
        if (viaDpi && kind != Kind.NOISE) put("dpi", Masks.CURRENT_DPI)
    })

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (base?.custom == true) "Изменить маскировку" else "Новая маскировка") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Вид", fontSize = 12.sp, color = Color.Gray)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kind.values().forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.title) }) }
                }
                Text("Отпечаток браузера (TLS)", fontSize = 12.sp, color = Color.Gray)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Masks.FINGERPRINTS.forEach { f -> FilterChip(selected = fp == f, onClick = { fp = f }, label = { Text(f) }) }
                }
                when (kind) {
                    Kind.FRAG, Kind.LADDER -> {
                        OutlinedTextField(packets, { packets = it.trim() }, label = { Text("Что дробить: tlshello или пакеты 1-3") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (kind == Kind.FRAG) {
                            OutlinedTextField(length, { length = it.trim() }, label = { Text("Размер куска, байт (10-30)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(delay, { delay = it.trim() }, label = { Text("Пауза, мс (5-10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(lengths, { lengths = it }, label = { Text("Ступеньки: размеры через запятую") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(delays, { delays = it }, label = { Text("Паузы для каждой ступеньки, мс") }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(maxSplit, { maxSplit = it.trim() }, label = { Text("Макс. кусков (пусто — без ограничения)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Плюс обход DPI этой сети", Modifier.weight(1f), fontSize = 14.sp); Switch(viaDpi, { viaDpi = it })
                        }
                    }
                    Kind.NOISE -> {
                        Text("Шум перед рукопожатием", fontSize = 12.sp, color = Color.Gray)
                        (Masks.NOISES + Masks.WG_NOISES).forEach { n ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = noiseJson.isBlank() && noise == n[0], onClick = { noise = n[0]; noiseJson = "" })
                                Text(n[1], fontSize = 13.sp)
                            }
                        }
                        OutlinedTextField(noiseJson, { noiseJson = it }, label = { Text("Или свой шум (JSON Xray finalmask noise)") },
                            placeholder = { Text("[{\"rand\":\"40-70\",\"delay\":\"1-3\"}]") }, modifier = Modifier.fillMaxWidth())
                        Text("Смена порта (только WireGuard / WARP)", fontSize = 12.sp, color = Color.Gray)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("" to "нет", Masks.HOP_LOCAL to "каждые 10-20 с", Masks.HOP_WARP to "по 54 портам WARP").forEach { (id, t) ->
                                FilterChip(selected = hop == id, onClick = { hop = id }, label = { Text(t) })
                            }
                        }
                    }
                    Kind.PLAIN -> Text("Только TLS-отпечаток, без дробления и шума.", fontSize = 12.sp, color = Color.Gray)
                }
                val preview = runCatching { build() }
                Text(preview.fold({ "Будет: " + describeMask(it) }, { it.message ?: "Ошибка" }),
                    fontSize = 12.sp, color = if (preview.isSuccess) Color.Gray else Bad)
                if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = Bad)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { build() }.onSuccess { m ->
                    saveMyMask(m, replacing = base?.takeIf { it.custom }?.id)
                    Toast.makeText(ctx, "Сохранено в «Мои маскировки»", Toast.LENGTH_SHORT).show(); onDismiss()
                }.onFailure { error = it.message ?: "Ошибка" }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
