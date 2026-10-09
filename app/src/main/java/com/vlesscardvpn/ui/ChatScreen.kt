package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.Assistant

/** «Помощник»: chat with the on-phone mask network and the app's measurements (core/Assistant). */
@Composable
fun ChatScreen() {
    val ctx = LocalContext.current
    val msgs by Assistant.messages.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    fun send() { val t = input; input = ""; Assistant.send(ctx, t) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(16.dp, 12.dp, 16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(Accent, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Text("🤖", fontSize = 18.sp) }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Помощник", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Управляет масками, режимами и настройками", fontSize = 12.sp, color = Color.Gray)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    Box(Modifier.widthIn(max = 320.dp).background(if (m.mine) Accent.copy(alpha = 0.85f) else Color(0xFF1F232C),
                        if (m.mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)).padding(10.dp)) {
                        Text(m.text, fontSize = 14.sp, color = Color.White)
                    }
                    if (m.buttons.isNotEmpty()) Row(Modifier.padding(top = 4.dp).horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.buttons.forEach { b -> AssistChip(onClick = { Assistant.run(ctx, b.cmd) }, label = { Text(b.label, fontSize = 12.sp) }) }
                    }
                }
            }
        }
        if (input.isEmpty()) Row(Modifier.fillMaxWidth().horizontalScrollSafe().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Что с сетью?", "Ускорь YouTube", "Облегчи маски", "Смени маску", "Проверь телефон", "Лучшие маски").forEach { q ->
                SuggestionChip(onClick = { Assistant.send(ctx, q) }, label = { Text(q, fontSize = 12.sp) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), shape = RoundedCornerShape(24.dp), placeholder = { Text("Спросите, например: что режут в моей сети?") },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = { send() }, enabled = input.isNotBlank()) { Text("➤") }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))
