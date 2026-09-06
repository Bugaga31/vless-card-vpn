package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.DiagnosticEngine
import com.vlesscardvpn.domain.DiagnosticResult
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Precision Field Instrument Diagnostic Screen:
 * Clean, readable inspection log verifying 3 authentic stages:
 * 1. Telegram MTProto Datacenter Response
 * 2. YouTube Video CDN Stream Speed & Throttling
 * 3. DNS-over-HTTPS & Anti-blocking Accessibility Pass Rate
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    repo: AppRepository,
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var diagResult by remember { mutableStateOf(DiagnosticResult()) }
    val settings by repo.settingsFlow.collectAsState()
    var isRunningAll by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val adaptiveRecommendation = remember(diagResult) {
        when {
            diagResult.tgStatus == DiagnosticResult.TestState.FAILED ->
                "Рекомендация: Протокол MTProto блокируется. Проверьте настройки Reality и выберите узел с активным TLS 1.3 Vision."
            diagResult.ytStatus == DiagnosticResult.TestState.FAILED || (diagResult.ytSpeedMbps in 0.1..4.0) ->
                "Рекомендация: Обнаружено замедление YouTube. Включите 'Блокировку QUIC' в настройках и проверьте MTU 1400."
            diagResult.rknPassRatePercent in 1..60 ->
                "Рекомендация: Частичная потеря доступности заблокированных ресурсов. Смените сервер выхода на европейский регион."
            diagResult.tgStatus == DiagnosticResult.TestState.SUCCESS && diagResult.ytSpeedMbps >= 10.0 ->
                "Статус: Туннель функционирует штатно. Маршрутизация и скорость соответствуют норме."
            else -> "Запустите комплексное тестирование для проверки фактической проходимости пакетов."
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Диагностика связи",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (!isRunningAll) {
                                isRunningAll = true
                                scope.launch {
                                    diagResult = diagResult.copy(
                                        tgStatus = DiagnosticResult.TestState.RUNNING,
                                        ytStatus = DiagnosticResult.TestState.RUNNING,
                                        rknStatus = DiagnosticResult.TestState.RUNNING
                                    )

                                    // 1. Telegram
                                    val (tgState, tgData) = DiagnosticEngine.runTelegramPulseTest()
                                    diagResult = diagResult.copy(
                                        tgStatus = tgState,
                                        tgPingMs = tgData.first,
                                        tgVerdict = tgData.second
                                    )

                                    // 2. YouTube
                                    val (ytState, ytMbps, ytVerdict) = DiagnosticEngine.runYouTubeStreamTest(settings.blockQuicYouTube)
                                    diagResult = diagResult.copy(
                                        ytStatus = ytState,
                                        ytSpeedMbps = ytMbps,
                                        ytVerdict = ytVerdict
                                    )

                                    // 3. RKN Echo
                                    val (rknState, passRate, rknVerdict) = DiagnosticEngine.runRknEchoTest()
                                    diagResult = diagResult.copy(
                                        rknStatus = rknState,
                                        rknPassRatePercent = passRate,
                                        rknVerdict = rknVerdict
                                    )

                                    isRunningAll = false
                                    snackbarHostState.showSnackbar("Тестирование завершено")
                                }
                            }
                        },
                        enabled = !isRunningAll,
                        colors = ButtonDefaults.buttonColors(containerColor = SignalOrange),
                        shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                        contentPadding = PaddingValues(horizontal = InstrumentDimens.space12, vertical = InstrumentDimens.space4),
                        modifier = Modifier.padding(end = InstrumentDimens.space8).height(36.dp)
                    ) {
                        if (isRunningAll) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Проверить всё", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space12),
            verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
        ) {
            // Recommendation Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(InstrumentDimens.space16),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SignalOrangeContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = SignalOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(InstrumentDimens.space12))
                    Text(
                        text = adaptiveRecommendation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Test 1: Telegram MTProto Response
            DiagnosticLogRow(
                title = "1. Telegram (MTProto)",
                description = "Прямой опрос дата-центров Telegram (149.154.167.99 / 91.108.56.165)",
                state = diagResult.tgStatus,
                metric = if (diagResult.tgPingMs > 0) "${diagResult.tgPingMs} мс" else "—",
                verdict = diagResult.tgVerdict,
                icon = Icons.Default.Send,
                onRun = {
                    scope.launch {
                        diagResult = diagResult.copy(tgStatus = DiagnosticResult.TestState.RUNNING)
                        val (tgState, tgData) = DiagnosticEngine.runTelegramPulseTest()
                        diagResult = diagResult.copy(
                            tgStatus = tgState,
                            tgPingMs = tgData.first,
                            tgVerdict = tgData.second
                        )
                    }
                }
            )

            // Test 2: YouTube CDN Video Stream
            DiagnosticLogRow(
                title = "2. YouTube (QUIC / TLS)",
                description = "Замер скорости запроса чанков youtubei.googleapis.com",
                state = diagResult.ytStatus,
                metric = if (diagResult.ytSpeedMbps > 0) "${diagResult.ytSpeedMbps} Мбит/с" else "—",
                verdict = diagResult.ytVerdict,
                icon = Icons.Default.PlayCircle,
                onRun = {
                    scope.launch {
                        diagResult = diagResult.copy(ytStatus = DiagnosticResult.TestState.RUNNING)
                        val (ytState, ytMbps, ytVerdict) = DiagnosticEngine.runYouTubeStreamTest(settings.blockQuicYouTube)
                        diagResult = diagResult.copy(
                            ytStatus = ytState,
                            ytSpeedMbps = ytMbps,
                            ytVerdict = ytVerdict
                        )
                    }
                }
            )

            // Test 3: Anti-Censorship & DoH Pass Rate
            DiagnosticLogRow(
                title = "3. Проходимость DoH и ресурсов",
                description = "Сквозная проверка пула адресов через DNS-over-HTTPS туннель",
                state = diagResult.rknStatus,
                metric = if (diagResult.rknPassRatePercent > 0) "${diagResult.rknPassRatePercent}%" else "—",
                verdict = diagResult.rknVerdict,
                icon = Icons.Default.Security,
                onRun = {
                    scope.launch {
                        diagResult = diagResult.copy(rknStatus = DiagnosticResult.TestState.RUNNING)
                        val (rknState, passRate, rknVerdict) = DiagnosticEngine.runRknEchoTest()
                        diagResult = diagResult.copy(
                            rknStatus = rknState,
                            rknPassRatePercent = passRate,
                            rknVerdict = rknVerdict
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun DiagnosticLogRow(
    title: String,
    description: String,
    state: DiagnosticResult.TestState,
    metric: String,
    verdict: String,
    icon: ImageVector,
    onRun: () -> Unit
) {
    val (statusColor, statusBg) = when (state) {
        DiagnosticResult.TestState.SUCCESS -> Pair(SemanticGreen, SemanticGreenBg)
        DiagnosticResult.TestState.WARNING -> Pair(SemanticAmber, SemanticAmberBg)
        DiagnosticResult.TestState.FAILED -> Pair(SemanticRed, SemanticRedBg)
        DiagnosticResult.TestState.RUNNING -> Pair(SignalOrange, SignalOrangeContainer)
        DiagnosticResult.TestState.IDLE -> Pair(GraphiteTertiary, MineralSurfaceSubtle)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(InstrumentDimens.space16)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(statusBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(InstrumentDimens.space12))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall)
                ) {
                    Text(
                        text = metric,
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space12))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Spacer(modifier = Modifier.height(InstrumentDimens.space12))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                Button(
                    onClick = onRun,
                    enabled = state != DiagnosticResult.TestState.RUNNING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MineralSurfaceSubtle,
                        contentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    if (state == DiagnosticResult.TestState.RUNNING) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = SignalOrange, strokeWidth = 2.dp)
                    } else {
                        Text("Тест", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
