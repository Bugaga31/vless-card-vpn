package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.Tunnel

/** Presentation only: selected routes and a generic tunnel check are not service-health proof. */
@Composable
fun ConnectionOverview(status: Tunnel.Status, summary: Store.Summary, cloud: String, openServers: () -> Unit) {
    var showCloud by rememberSaveable { mutableStateOf(false) }
    val backgroundCoverage by com.vlesscardvpn.core.BackgroundCatalogQueue.progress.collectAsState()
    val searchCoverage by com.vlesscardvpn.core.AutoSearchReport.coverage.collectAsState()
    val history by com.vlesscardvpn.core.ServiceHealth.history.collectAsState()
    val health by com.vlesscardvpn.core.ServiceHealth.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(5000) }
        }
    }
    fun serviceLabel(value: Boolean?): String = when (com.vlesscardvpn.core.ServiceHealth.verdict(value, health, now)) {
        com.vlesscardvpn.core.ServiceHealth.Verdict.PASS -> "проверен"
        com.vlesscardvpn.core.ServiceHealth.Verdict.FAIL -> "недоступен в проверке"
        com.vlesscardvpn.core.ServiceHealth.Verdict.UNKNOWN -> "не удалось проверить"
        com.vlesscardvpn.core.ServiceHealth.Verdict.STALE -> "результат устарел"
        com.vlesscardvpn.core.ServiceHealth.Verdict.CHECKING -> "проверяется"
    }
    val scheme = MaterialTheme.colorScheme
    val connecting = status.state == Tunnel.State.CONNECTING
    val connected = status.state == Tunnel.State.CONNECTED
    val tint = when {
        status.state == Tunnel.State.ERROR -> Bad
        connected && status.checkOk == false -> Warn
        connected && status.checkOk == true -> Good
        else -> scheme.primary
    }
    val title = when {
        connecting -> "Ищем вашу волну"
        status.state == Tunnel.State.ERROR -> "Нужен другой маршрут"
        connected && status.checkOk == false -> "Туннель есть. Проверка не прошла"
        connected && status.checkOk == true -> "Туннель передаёт данные"
        connected -> "Туннель включён"
        else -> "Готовы к подключению"
    }
    Card(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = .28f)),
        colors = CardDefaults.cardColors(containerColor = scheme.surface)) {
        Column(Modifier.background(Brush.verticalGradient(listOf(tint.copy(alpha = .06f), Color.Transparent)))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when { connecting -> "ПОДБОР МАРШРУТА"; connected -> "ТЕКУЩЕЕ СОЕДИНЕНИЕ"; else -> "ВАША СВЯЗЬ" },
                color = tint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (connecting) LiveProgress(stop = false) { it.running }
            Text(when {
                connecting -> "Сравниваем маршруты. Поиск можно отменить кнопкой выше."
                connected -> "Проверка туннеля не гарантирует работу Telegram и видео YouTube. История сервисных проверок — ниже, в объяснении маршрута."
                status.state == Tunnel.State.ERROR -> "Смотрите причину выше. Можно повторить подключение или открыть серверы."
                else -> "Один тап — подбор с учётом опыта этой сети и облачных подсказок."
            }, color = scheme.onSurfaceVariant, fontSize = 16.sp)
            Text("Auto проверяет передачу через независимые HTTPS-ресурсы. Это не подтверждение Telegram или YouTube.", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Text("Telegram MTProto: ${serviceLabel(health.telegram)}", fontSize = 14.sp)
            Text("YouTube-медиа: ${serviceLabel(health.youtube)}", fontSize = 14.sp)
            if (!health.checking && health.checkedAt > 0 && health.telegram != true)
                Text(com.vlesscardvpn.core.ServiceProbePolicy.reason(health.telegramReason), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            if (!health.checking && health.checkedAt > 0 && health.youtube != true)
                Text(com.vlesscardvpn.core.ServiceProbePolicy.reason(health.youtubeReason), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Text("Проверка транспорта без аккаунта; не проверяет сообщения и звонки. Медиа: до 128 КиБ, бюджет ${health.budgetMs / 1000} с." +
                if (health.checkedAt > 0) " Проверено ${(now - health.checkedAt).coerceAtLeast(0) / 1000} с назад." else " Результата ещё нет.",
                color = scheme.onSurfaceVariant, fontSize = 12.sp)
            if (backgroundCoverage.total > 0) Text(backgroundCoverage.text() +
                ". Остальной каталог проверяется по очереди; паузы при активной загрузке, нагреве и ограничениях питания.",
                color = scheme.onSurfaceVariant, fontSize = 12.sp)
            if (searchCoverage.catalog > 0) Text("Последний поиск: " + searchCoverage.text() +
                ". Размер финального пула не ограничивает каталог; непроверенные маршруты не объявлены нерабочими.",
                color = scheme.onSurfaceVariant, fontSize = 12.sp)
            HorizontalDivider(color = scheme.outline.copy(alpha = .3f))
            // Stack on narrow screens and with enlarged system text. Never label the pool as active tunnels.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
                if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PoolFact("В выбранном пуле", summary.selectedCount)
                    PoolFact("Всего в списке", summary.total)
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { PoolFact("В выбранном пуле", summary.selectedCount) }
                    Column(Modifier.weight(1f)) { PoolFact("Всего в списке", summary.total) }
                }
            }
            Text("Размер пула — не число одновременно работающих соединений.", color = scheme.onSurfaceVariant, fontSize = 14.sp)
            OutlinedButton(openServers, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Открыть серверы", fontSize = 16.sp) }
            TextButton(onClick = { showCloud = !showCloud }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (showCloud) "Скрыть состояние облака ↑" else "Состояние облака ↓", fontSize = 14.sp)
            }
            if (showCloud) {
                Text("Снимок каталога: ${summary.total} уникальных → TCP ${summary.tcpAccessible} → HTTPS ${summary.working} → сервисы ${summary.serviceVerified} → с измеренной скоростью ${summary.measured}",
                    color = scheme.onSurfaceVariant, fontSize = 13.sp)
                Text("Это результаты последних проверок, не текущая скорость. Снимку ${(now - summary.snapshotAt).coerceAtLeast(0) / 1000} с.", fontSize = 12.sp)
                history.take(4).forEach { event ->
                    val label = when (event.kind) {
                        com.vlesscardvpn.core.ServiceHealth.EventKind.SEARCH -> "поиск"
                        com.vlesscardvpn.core.ServiceHealth.EventKind.CHECK -> "проверка сервисов"
                        com.vlesscardvpn.core.ServiceHealth.EventKind.UNKNOWN -> "ресурс проверки не дал подтверждения"
                        com.vlesscardvpn.core.ServiceHealth.EventKind.FAILURE -> "сервисная проверка не прошла"
                        com.vlesscardvpn.core.ServiceHealth.EventKind.CONNECTED -> "цепочка подключена / переподключена"
                        com.vlesscardvpn.core.ServiceHealth.EventKind.NETWORK_CHANGE -> "смена физической сети"
                    }
                    Text("$label · ${(now - event.at).coerceAtLeast(0) / 1000} с назад", fontSize = 12.sp)
                }
            }
            if (showCloud) Text(cloud.ifBlank { "Статус ещё не получен. Облако не подтверждает доступность сервисов в вашей сети." },
                color = scheme.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

@Composable
private fun PoolFact(label: String, value: Int) {
    Text(value.toString(), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
