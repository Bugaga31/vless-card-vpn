package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

enum class DiagnosticTab(val label: String) {
    WHY_NOT_WORKING("Связь"), PRE_CALL_TEST("Стабильность"), SERVICE_AUDIT("Telegram / YouTube")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(repo: AppRepository, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val settings by repo.settingsFlow.collectAsState()
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val vpn by VlessVpnService.vpnStats.collectAsState()
    val active = vpn.activeConfig ?: configs.firstOrNull { it.isActive }
    var tab by remember { mutableStateOf(DiagnosticTab.SERVICE_AUDIT) }
    val colors = MaterialTheme.colorScheme
    Scaffold(containerColor = colors.background, topBar = {
        TopAppBar(title = { Text("Качество вашей связи") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 16.dp,
                containerColor = colors.background) {
                DiagnosticTab.values().forEach { item ->
                    Tab(selected = tab == item, onClick = { tab = item }, text = { Text(item.label) })
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (tab) {
                    DiagnosticTab.SERVICE_AUDIT -> ServiceAuditPanel()
                    DiagnosticTab.WHY_NOT_WORKING -> {
                        val scope = rememberCoroutineScope()
                        var target by remember { mutableStateOf("youtube.com") }
                        var report by remember { mutableStateOf<WhyNotWorkingReport?>(null) }
                        var busy by remember { mutableStateOf(false) }
                        var error by remember { mutableStateOf<String?>(null) }
                        Text("От сети до целевого сайта", style = MaterialTheme.typography.titleLarge)
                        Text("Пошаговая диагностика. Выводы о причинах требуют проверки; отдельный HTTPS-ответ не подтверждает каждый маршрут.",
                            color = colors.onSurfaceVariant)
                        OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text("Домен без https://") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                            busy = true; error = null; report = null
                            scope.launch {
                                try {
                                    report = DiagnosticEngine.runWhyNotWorkingDiagnostic(context, active, settings,
                                        target.trim().ifBlank { "youtube.com" })
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "Проверку не удалось завершить" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "Проверяем…" else "Проверить связь") }
                        error?.let { Text(it, color = colors.error) }
                        report?.let { result ->
                            DiagnosticInfoCard("Результат диагностики", result.confirmedFact + "\n" + result.possibleCause)
                            result.stages.forEach { stage ->
                                DiagnosticInfoCard(stage.title, "${stage.details}\n${stage.methodUsed}\n${stage.errorDetails.orEmpty()}")
                            }
                            Text("Следующий шаг: ${result.recommendedActionTitle}", color = colors.onSurfaceVariant)
                        }
                    }
                    DiagnosticTab.PRE_CALL_TEST -> {
                        val scope = rememberCoroutineScope()
                        var result by remember { mutableStateOf(CallQualityTestResult()) }
                        var job by remember { mutableStateOf<Job?>(null) }
                        var busy by remember { mutableStateOf(false) }
                        Text("Стабильность HTTPS", style = MaterialTheme.typography.titleLarge)
                        Text("10 запросов к контрольным сайтам. Это не тест голосового звонка; длительность зависит от таймаутов.", color = colors.onSurfaceVariant)
                        Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                            if (busy) job?.cancel() else {
                                busy = true
                                job = scope.launch {
                                    try { result = DiagnosticEngine.runPreCallQualityTest { result = it } }
                                    catch (e: CancellationException) { throw e }
                                    finally { busy = false }
                                }
                            }
                        }) { Text(if (busy) "Остановить проверку" else "Проверить стабильность") }
                        DiagnosticInfoCard("Средний отклик", if (result.avgLatencyMs > 0) "${result.avgLatencyMs} мс" else "Нет измерения")
                        DiagnosticInfoCard("Успешные запросы", if (result.totalProbes > 0) "${result.successfulProbes}/${result.totalProbes}" else "Нет измерения")
                        Text(result.verdict, color = colors.onSurfaceVariant)
                        Text(result.limitationNote, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceAuditPanel() {
    val scope = rememberCoroutineScope()
    val vpn by VlessVpnService.vpnStats.collectAsState()
    val key = "${vpn.status}:${vpn.activeConfig?.id}:${vpn.connectedSinceTimestamp}"
    var results by remember(key) { mutableStateOf<List<ServiceReachabilityResult>>(emptyList()) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var extraResult by remember(key) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(key) { job?.cancel(); busy = false }

    Text("Telegram и YouTube", style = MaterialTheme.typography.titleLarge)
    Text("По три HTTPS-запроса к каждому сервису, с отдельной медианой отклика. Это не ICMP-пинг и не замер скорости видео.", color = colors.onSurfaceVariant)
    Text(if (vpn.status == VpnStatus.CONNECTED)
        "VPN подключён. Запросы используют текущую маршрутизацию Android; исключения VPN могут влиять на путь."
        else "VPN не подключён: результат относится к текущей сети, не к выбранному VPN-серверу.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    Text("Telegram: telegram.org (веб-сайт, не MTProto). YouTube: youtube.com/generate_204 (не воспроизведение видео). Сервисы видят адрес исходящего соединения.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
        if (busy) job?.cancel() else {
            busy = true; results = emptyList(); error = null
            job = scope.launch {
                try {
                    val measured = ServiceReachability.checkBoth()
                    val current = VlessVpnService.vpnStats.value
                    if (key == "${current.status}:${current.activeConfig?.id}:${current.connectedSinceTimestamp}") results = measured
                } catch (_: TimeoutCancellationException) { error = "Лимит 15 секунд исчерпан. Повторите проверку." }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "Не удалось завершить проверку" }
                finally { busy = false }
            }
        }
    }) { Text(if (busy) "Отменить проверку" else "Проверить Telegram и YouTube") }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = colors.error) }
    ServiceTarget.values().forEach { target ->
        val result = results.firstOrNull { it.target == target }
        DiagnosticInfoCard(target.label,
            if (result == null) "Нет измерения" else
                "${result.medianMs?.let { "$it мс · медиана успешных запросов" } ?: "Нет успешного отклика"}\n${result.summary}\n" +
                    result.samples.mapIndexed { index, sample ->
                        "${index + 1}: ${sample.httpCode?.let { "HTTP $it" } ?: sample.error.orEmpty()}" +
                            (sample.latencyMs?.let { " · $it мс" } ?: "")
                    }.joinToString("\n"))
    }
    Text("Это результат ручного запуска, не непрерывный мониторинг. Сбой одного сайта не означает отказ всего VPN. Этот тест не переключает сервер автоматически.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
        busy = true; extraResult = null
        job = scope.launch {
            try {
                val result = DiagnosticEngine.runRknEchoTest()
                extraResult = "${result.third}\nЭто HTTP-проверка списка сайтов, не отдельный тест DoH."
            } catch (e: CancellationException) { throw e }
            finally { busy = false }
        }
    }) { Text("Дополнительно: проверить список сайтов") }
    extraResult?.let { Text(it, color = colors.onSurfaceVariant) }
}

@Composable
private fun DiagnosticInfoCard(title: String, body: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
