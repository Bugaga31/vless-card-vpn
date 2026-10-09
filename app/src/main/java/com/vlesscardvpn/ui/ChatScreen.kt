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
        val dl by com.vlesscardvpn.core.LocalLlm.download.collectAsState()
        val cfg by com.vlesscardvpn.core.Store.settings.collectAsState()
        val model = remember(cfg.llmModel, dl.running) { Assistant.llm(ctx) }
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Помощник", fontSize = 20.sp)
                Text(if (model != null) "ИИ на телефоне: ${model.title} ✓" else "Ответы по шаблонам · можно скачать ИИ-модель", fontSize = 12.sp, color = Color.Gray)
            }
            if (!dl.running) TextButton(onClick = { Assistant.send(ctx, "скачай модель") }) { Text(if (model != null) "Модель" else "Скачать ИИ") }
        }
        if (dl.running && dl.total > 0) Column(Modifier.padding(horizontal = 16.dp)) {
            LinearProgressIndicator(progress = { dl.done.toFloat() / dl.total }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Скачиваю модель: ${dl.done * 100 / dl.total}% (${"%.2f".format(dl.done / 1e9)} из ${"%.2f".format(dl.total / 1e9)} ГБ)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { Assistant.run(ctx, "!dlstop") }) { Text("Стоп") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
                    Box(Modifier.widthIn(max = 320.dp).background(if (m.mine) Accent.copy(alpha = 0.85f) else Color(0xFF1F232C), RoundedCornerShape(14.dp)).padding(10.dp)) {
                        Text(m.text, fontSize = 14.sp, color = Color.White)
                    }
                    if (m.buttons.isNotEmpty()) Row(Modifier.padding(top = 4.dp).horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.buttons.forEach { b -> AssistChip(onClick = { Assistant.run(ctx, b.cmd) }, label = { Text(b.label, fontSize = 12.sp) }) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Спросите, например: что режут в моей сети?") },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            Spacer(Modifier.width(6.dp))
            Button(onClick = { send() }, enabled = input.isNotBlank()) { Text("➤") }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))
