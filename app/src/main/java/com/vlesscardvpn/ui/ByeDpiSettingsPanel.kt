package com.vlesscardvpn.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.ByeDpiArgs
import com.vlesscardvpn.domain.ConnectCheckMode

/** Check mode ("только пинг"), the user's own ByeByeDPI-style strategy and the fake-SNI mask domain. */
@Composable
fun ByeDpiSettingsPanel(repo: AppRepository, settings: AppSettings) {
    var custom by remember(settings.byeDpiCustomArgs) { mutableStateOf(settings.byeDpiCustomArgs) }
    var mask by remember(settings.byeDpiMaskDomain) { mutableStateOf(settings.byeDpiMaskDomain) }
    val mode = ConnectCheckMode.normalize(settings.connectCheckMode)
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Проверка и обход блокировок", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Проверка при подключении", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ConnectCheckMode.PING, ConnectCheckMode.HTTPS).forEach { value ->
                    FilterChip(selected = mode == value, onClick = { repo.updateSettings { it.copy(connectCheckMode = value) } },
                        label = { Text(ConnectCheckMode.label(value)) })
                }
            }
            Text(if (mode == ConnectCheckMode.PING)
                    "Быстро: «Авто» берёт до 5 серверов с лучшим пингом и принимает только тот, через который реально открылся HTTPS. Ручное подключение — сразу, HTTPS проверяется в фоне."
                else "Медленнее: каждый маршрут без HTTPS отклоняется, «Авто» перебирает стратегии ByeDPI на каждом сервере.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Без сервера, если серверы не работают", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Как ByeByeDPI: трафик идёт напрямую через встроенные ByeDPI и zapret (tpws), стратегии перебираются, пока не загрузится YouTube. Помогает от замедления и DPI, но не от блокировки по IP.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = settings.directFallback, onCheckedChange = { v -> repo.updateSettings { it.copy(directFallback = v) } })
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Text("Своя стратегия ByeDPI", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Как в ByeByeDPI: например «-d1 -f-1 -t8 -n {sni}». {sni} заменяется доменом маскировки. Пусто — встроенные стратегии. Применяется к ручному подключению и «Авто».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = custom, onValueChange = { v ->
                    custom = v.take(ByeDpiArgs.MAX_LENGTH); repo.updateSettings { it.copy(byeDpiCustomArgs = custom) }
                }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("-o1 -At,r,s -d1") })
            if (custom.isNotBlank()) {
                val parsed = remember(custom, mask) { ByeDpiArgs.parse(custom, mask) }
                Text(parsed.fold({ "✓ Будет запущено: " + it.joinToString(" ") +
                        ByeDpiArgs.warnings(it).joinToString("") { w -> "\n⚠ $w" } }, { "✗ " + it.message }),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (custom.isNotBlank()) AssistChip(onClick = { custom = ""; repo.updateSettings { it.copy(byeDpiCustomArgs = "") } },
                    label = { Text("Очистить") })
                ByeDpiArgs.EXAMPLES.forEach { example ->
                    AssistChip(onClick = { custom = example; repo.updateSettings { it.copy(byeDpiCustomArgs = example) } },
                        label = { Text(example, fontFamily = FontFamily.Monospace) })
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Text("Маскировка (фейк-SNI)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Фейковый ClientHello с этим доменом видит DPI; до сервера он не доходит (TTL 8). Используется стратегиями «маскировка» и {sni}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ByeDpiArgs.MASK_DOMAINS.forEach { domain ->
                    FilterChip(selected = settings.byeDpiMaskDomain == domain,
                        onClick = { mask = domain; repo.updateSettings { it.copy(byeDpiMaskDomain = domain) } },
                        label = { Text(domain) })
                }
            }
            OutlinedTextField(value = mask, onValueChange = { v ->
                    mask = v.trim().lowercase().take(253)
                    if (ByeDpiArgs.maskDomain(mask) == mask) repo.updateSettings { it.copy(byeDpiMaskDomain = mask) }
                }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Свой домен маскировки") },
                isError = ByeDpiArgs.maskDomain(mask) != mask)
        }
    }
}
