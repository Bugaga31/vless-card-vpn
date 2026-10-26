package com.vlesscardvpn.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.ai.LocalAi
import com.vlesscardvpn.ai.LocalAiPolicy

/** Compact overview first; model size/consent and destructive controls stay in the expanded area. */
@Composable
fun LocalAiPanel() {
    val ctx = LocalContext.current
    val state by LocalAi.state.collectAsState()
    var control by remember { mutableStateOf(false) }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { LocalAi.init(ctx); control = com.vlesscardvpn.ai.LocalAutoControl.enabled }
    LaunchedEffect(state.enabled) { control = com.vlesscardvpn.ai.LocalAutoControl.enabled }
    val model = LocalAiPolicy.model(state.model)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Локальный интеллект", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (state.enabled) if (model == LocalAiPolicy.compact) "Qwen 0.5B · на устройстве" else "Huihui 27B · на устройстве"
                    else "Чат выключен · Auto доступен", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Скрыть" else "Настроить") }
        }
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.busy) {
            if (state.progress >= 0) {
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Загрузка ${state.progress}%", style = MaterialTheme.typography.bodyMedium)
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = { LocalAi.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Остановить модель") }
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text("Модель", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalAiPolicy.models.forEach { m ->
                        FilterChip(state.model == m.id, { LocalAi.select(ctx, m.id) }, enabled = !state.busy,
                            label = { Text(if (m == LocalAiPolicy.compact) "0.5B · 491 МБ" else "27B · 10,9 ГБ") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text(if (model == LocalAiPolicy.compact) "Для 64-битных устройств: от 3 ГиБ ОЗУ, свободно от 1200 МиБ. Качество ответов ограничено."
                    else "Эксперимент: от 20 ГиБ ОЗУ, свободно от 13 ГиБ. Работа Huihui на телефоне пока не подтверждена.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.busy) {
                    if (!state.downloaded) Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Скачать выбранную модель") }
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Разговорный чат", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(state.enabled, { LocalAi.enable(ctx, it) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Нейроконтроль Auto", style = MaterialTheme.typography.bodyLarge)
                        Text("Порядок маршрутов, маски и энергия приложения", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(control, {
                        control = it; com.vlesscardvpn.ai.LocalAutoControl.enable(ctx, it)
                        if (it) LocalAi.preparePolicy(ctx)
                    })
                }
                Text("Модель предлагает план и может запускать Auto, проверки и подбор масок по запросу. Сетевой результат подтверждают только измерения. Отключение VPN — по подтверждению.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.downloaded && !state.busy) TextButton(onClick = { confirmDelete = true }) { Text("Удалить модель и историю") }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Загрузить модель?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(model.title, fontWeight = FontWeight.SemiBold)
            Text("Файл ${model.bytes / 1000000} МБ с Hugging Face. Возможен платный мобильный трафик. Проверяется SHA-256. Переписка не отправляется внешнему LLM API.")
            Text("Генерация использует CPU и может нагревать телефон. При включении чата включится ограниченный нейроконтроль Auto и энергии приложения; его можно выключить отдельно. Модель не получает ключи VPN.")
        } }, confirmButton = { TextButton(onClick = { confirm = false; LocalAi.download(ctx) }) { Text("Подтвердить загрузку") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить локальную модель?") },
        text = { Text("Удалится выбранный GGUF и очистится история чата в памяти. VPN-настройки и серверы сохранятся.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; LocalAi.delete(ctx) }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}
