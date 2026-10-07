package com.vlesscardvpn.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.WarpKeys
import com.vlesscardvpn.model.Settings

private enum class KeyFilter(val title: String) { ALL("Все"), OK("Рабочие"), NEW("Не проверены"), OWN("Свои"), OFF("Выключены") }

/**
 * «Ключи WARP+»: every key (own + built-in) with what Cloudflare said about it last time. A checkbox decides
 * whether the key takes part in automatic setup; «Подключить» sets WARP up with exactly this key.
 */
@Composable
fun WarpKeysDialog(onDismiss: () -> Unit) {
    val s by Store.settings.collectAsState()
    val busy by Actions.running.collectAsState()
    fun set(f: (Settings) -> Settings) = Store.update { it.copy(settings = f(it.settings)) }
    val own = remember(s.warpKeys) { WarpKeys.parse(s.warpKeys) }
    val all = remember(own, s.warpBuiltinKeys) { (own + if (s.warpBuiltinKeys) WarpKeys.BUILT_IN else emptyList()).distinct() }
    var filter by remember { mutableStateOf(KeyFilter.ALL) }
    val now = System.currentTimeMillis()
    fun st(k: String) = WarpKeys.status(s.warpKeyStatus, k, now)
    val shown = all.filter { k ->
        when (filter) {
            KeyFilter.ALL -> true
            KeyFilter.OK -> st(k) == WarpKeys.OK
            KeyFilter.NEW -> st(k) == null
            KeyFilter.OWN -> k in own
            KeyFilter.OFF -> k in s.warpKeyOff
        }
    }.sortedBy { k -> when (st(k)) { WarpKeys.OK -> 0; null -> 1; WarpKeys.FULL -> 2; else -> 3 } }
    val okCount = all.count { st(it) == WarpKeys.OK }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ключи WARP+ · ${all.size}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Рабочих: $okCount · заняты: ${all.count { st(it) == WarpKeys.FULL }} · не проверены: ${all.count { st(it) == null }}. " +
                    "Галочка — ключ участвует в автонастройке. «Подключить» — настроить WARP именно с этим ключом.", fontSize = 12.sp, color = Color.Gray)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyFilter.values().forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { set { it.copy(warpKeyOff = it.warpKeyOff - shown.toSet()) } }) { Text("Включить все") }
                    TextButton(onClick = { set { it.copy(warpKeyOff = (it.warpKeyOff + shown).distinct()) } }) { Text("Выключить все") }
                }
                if (shown.isEmpty()) Text("Здесь пусто.", fontSize = 13.sp, color = Color.Gray)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it }) { k ->
                        val on = k !in s.warpKeyOff
                        Row(Modifier.fillMaxWidth().clickable { set { it.copy(warpKeyOff = if (on) it.warpKeyOff + k else it.warpKeyOff - k) } },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { v -> set { it.copy(warpKeyOff = if (v) it.warpKeyOff - k else (it.warpKeyOff + k).distinct()) } })
                            Column(Modifier.weight(1f)) {
                                Text(k, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                val (txt, col) = when (st(k)) {
                                    WarpKeys.OK -> "✓ работает" to Good
                                    WarpKeys.FULL -> "занят: слишком много устройств" to Warn
                                    WarpKeys.BAD -> "✗ недействителен" to Bad
                                    else -> "не проверен" to Color.Gray
                                }
                                Text((if (k in own) "свой · " else "") + txt, fontSize = 11.sp, color = col)
                            }
                            TextButton(onClick = { Actions.setupWarp(onlyKey = k); onDismiss() }, enabled = !busy && st(k) != WarpKeys.BAD) { Text("Подключить") }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
