package com.vlesscardvpn.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NetworkDiagnosticsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun exportText(rows: List<String>): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("unknown") ?: "unknown"
        return NetworkDiagnosticLog.report(version, Build.VERSION.SDK_INT, rows)
    }
    NetworkDiagnosticsCard(running, note,
        onCheck = { scope.launch {
            running = true
            try {
                if (TunnelHealthChecker.activeProxy == null) note = "Сначала подключите VPN. Прямая проверка не подменяет проверку туннеля."
                else {
                    val result = TunnelHealthChecker.check()
                    note = "HTTPS: ${if (result.internet) "доступен" else "ошибка"}. YouTube: ${if (result.youtube) "доступен" else "ошибка"}. Telegram веб: ${if (result.telegram) "доступен" else "ошибка"}."
                }
            } finally { running = false }
        } },
        onShow = { scope.launch { report = exportText(withContext(Dispatchers.IO) { NetworkDiagnosticLog.ring?.read().orEmpty() }) } })
    report?.let { text ->
        NetworkReportDialog(text, onDismiss = { report = null }, onShare = {
            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "VLESS Card Lab — журнал сети")
            }, "Отправить журнал")) }.onFailure {
                android.widget.Toast.makeText(context, "Нет приложения для отправки. Журнал сохранён.", android.widget.Toast.LENGTH_LONG).show()
            }
        }, onClear = { scope.launch {
            withContext(Dispatchers.IO) { NetworkDiagnosticLog.ring?.clear() }; report = null
        } })
    }
}

@Composable
fun NetworkDiagnosticsCard(running: Boolean = false, note: String = "", onCheck: () -> Unit = {}, onShow: () -> Unit = {}) {
    Card(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Диагностика сети", style = MaterialTheme.typography.titleMedium)
            Text("Журнал последних 200 событий: режим, этап ошибки, HTTP и время ответа. Без ключей, адресов серверов и ссылок подписок.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Проверка идёт через выбранный сервер. Она не проверяет видео, звонки и MTProto.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.isNotEmpty()) Text(note)
            Button(onClick = onCheck, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Проверяем HTTPS…" else "Проверить подключение")
            }
            OutlinedButton(onClick = onShow, modifier = Modifier.fillMaxWidth()) { Text("Журнал и отправка отчёта") }
        }
    }
}

@Composable
fun NetworkReportDialog(text: String, onDismiss: () -> Unit = {}, onShare: () -> Unit = {}, onClear: () -> Unit = {}) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Журнал сети") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text("Проверьте отчёт перед отправкой. Отправка только по вашему действию.")
            TextButton(onClick = onClear) { Text("Очистить журнал") }
            Spacer(Modifier.height(12.dp)); Text(text, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = onShare) { Text("Поделиться") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
