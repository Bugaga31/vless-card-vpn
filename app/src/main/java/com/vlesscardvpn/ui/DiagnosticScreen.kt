package com.vlesscardvpn.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.*
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class DiagnosticTab(val label: String) {
    WHY_NOT_WORKING("Почему не работает?"),
    PRE_CALL_TEST("Перед звонком"),
    SERVICE_AUDIT("Службы")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    repo: AppRepository,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState()
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val activeConfig = configs.firstOrNull { it.isActive } ?: configs.firstOrNull()

    var selectedTab by remember { mutableStateOf(DiagnosticTab.WHY_NOT_WORKING) }
    val snackbarHostState = remember { SnackbarHostState() }

    // State for "Почему не работает?"
    var isRunningWhyNotWorking by remember { mutableStateOf(false) }
    var whyReport by remember { mutableStateOf<WhyNotWorkingReport?>(null) }
    var customTargetDomain by remember { mutableStateOf("youtube.com") }

    // State for "Проверить перед звонком"
    var isRunningPreCall by remember { mutableStateOf(false) }
    var preCallResult by remember { mutableStateOf(CallQualityTestResult()) }
    var preCallJob by remember { mutableStateOf<Job?>(null) }

    // State for Service Audit (Telegram, YouTube, RKN)
    var diagResult by remember { mutableStateOf(DiagnosticResult()) }
    var isRunningServices by remember { mutableStateOf(false) }

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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space8)
        ) {
            // Tab Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
            ) {
                DiagnosticTab.values().forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SignalOrangeContainer,
                            selectedLabelColor = SignalOrangeContent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space12))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
            ) {
                when (selectedTab) {
                    DiagnosticTab.WHY_NOT_WORKING -> {
                        // 1. «ПОЧЕМУ НЕ РАБОТАЕТ?» Tab
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(InstrumentDimens.space16), verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space12)) {
                                Text(
                                    text = "ПОСЛЕДОВАТЕЛЬНАЯ ПРОВЕРКА СВЯЗИ",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "Пошагово проверяет все уровни: Сеть → DNS → Сервер → Туннель → Целевой сайт.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customTargetDomain,
                                        onValueChange = { customTargetDomain = it },
                                        label = { Text("Целевой ресурс", fontSize = 12.sp) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = SignalOrange,
                                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                        )
                                    )

                                    Button(
                                        onClick = {
                                            if (!isRunningWhyNotWorking) {
                                                isRunningWhyNotWorking = true
                                                scope.launch {
                                                    whyReport = DiagnosticEngine.runWhyNotWorkingDiagnostic(
                                                        context = context,
                                                        config = activeConfig,
                                                        settings = settings,
                                                        targetDomain = customTargetDomain.ifBlank { "youtube.com" }
                                                    )
                                                    isRunningWhyNotWorking = false
                                                    snackbarHostState.showSnackbar("Диагностика завершена")
                                                }
                                            }
                                        },
                                        enabled = !isRunningWhyNotWorking,
                                        colors = ButtonDefaults.buttonColors(containerColor = SignalOrange),
                                        shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                                        modifier = Modifier.height(52.dp)
                                    ) {
                                        if (isRunningWhyNotWorking) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        } else {
                                            Text("Запуск")
                                        }
                                    }
                                }
                            }
                        }

                        if (whyReport != null) {
                            val report = whyReport!!

                            // Confirmed Fact & Possible Cause Card
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Column(modifier = Modifier.padding(InstrumentDimens.space16), verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.FactCheck, contentDescription = null, tint = SignalOrange, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                                        Text("ПОДТВЕРЖДЁННЫЙ ФАКТ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = SignalOrange)
                                    }
                                    Text(text = report.confirmedFact, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)

                                    if (report.possibleCause.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(InstrumentDimens.space4))
                                        Text(text = "Возможная причина: ${report.possibleCause}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    if (report.recommendedActionTitle.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(InstrumentDimens.space8))
                                        Button(
                                            onClick = {
                                                when (report.recommendedActionType) {
                                                    RecommendedActionType.RESTORE_RESCUE_PROFILE -> {
                                                        scope.launch {
                                                            val engine = AutoPilotEngine(context, repo.getDatabase())
                                                            engine.restoreLastWorkingProfile()
                                                            snackbarHostState.showSnackbar("Спасательный профиль восстановлен")
                                                        }
                                                    }
                                                    RecommendedActionType.RETRY_TUNNEL -> {
                                                        activeConfig?.let { VlessVpnService.startVpn(context, it) }
                                                    }
                                                    else -> {}
                                                }
                                            },
                                            shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                                            colors = ButtonDefaults.buttonColors(containerColor = SignalOrange),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(report.recommendedActionTitle)
                                        }
                                    }
                                }
                            }

                            // 5 Distinct Diagnostic Stages
                            report.stages.forEach { stage ->
                                StageCardItem(stage = stage)
                            }
                        }
                    }

                    DiagnosticTab.PRE_CALL_TEST -> {
                        // 2. «ПРОВЕРИТЬ ПЕРЕД ЗВОНКОМ» Tab
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(InstrumentDimens.space16), verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space12)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "ПРОВЕРКА ПЕРЕД ЗВОНКОМ",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            letterSpacing = 0.8.sp
                                        )
                                        Text(
                                            text = "Серия контрольных HTTPS-запросов (до 15 сек, трафик < 50 КБ) для оценки стабильности и разброса задержки.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                                ) {
                                    Button(
                                        onClick = {
                                            if (isRunningPreCall) {
                                                preCallJob?.cancel()
                                                isRunningPreCall = false
                                            } else {
                                                isRunningPreCall = true
                                                preCallJob = scope.launch {
                                                    val res = DiagnosticEngine.runPreCallQualityTest { progress ->
                                                        preCallResult = progress
                                                    }
                                                    preCallResult = res
                                                    isRunningPreCall = false
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isRunningPreCall) SemanticRed else SignalOrange
                                        ),
                                        shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(if (isRunningPreCall) "Остановить тест" else "Начать тест (15 сек)")
                                    }
                                }
                            }
                        }

                        // Pre-call measurement stats
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(InstrumentDimens.space16), verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space12)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("УСПЕШНОСТЬ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = if (preCallResult.totalProbes > 0) "${preCallResult.successRatePercent}%" else "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("СРЕДНЯЯ ЗАДЕРЖКА", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = if (preCallResult.avgLatencyMs > 0) "${preCallResult.avgLatencyMs} мс" else "—",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outline))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("РАЗБРОС", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = if (preCallResult.latencyVarianceMs > 0) "±${preCallResult.latencyVarianceMs} мс" else "0 мс",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                                Text(
                                    text = preCallResult.verdict,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )

                                Text(
                                    text = preCallResult.limitationNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = GraphiteTertiary
                                )
                            }
                        }
                    }

                    DiagnosticTab.SERVICE_AUDIT -> {
                        // 3. Service Audit Tab (Telegram, YouTube, RKN)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    if (!isRunningServices) {
                                        isRunningServices = true
                                        scope.launch {
                                            val (tgState, tgData) = DiagnosticEngine.runTelegramPulseTest()
                                            diagResult = diagResult.copy(tgStatus = tgState, tgPingMs = tgData.first, tgVerdict = tgData.second)

                                            val (ytState, ytMbps, ytVerdict) = DiagnosticEngine.runYouTubeStreamTest(settings.blockQuicYouTube)
                                            diagResult = diagResult.copy(ytStatus = ytState, ytSpeedMbps = ytMbps, ytVerdict = ytVerdict)

                                            val (rknState, passRate, rknVerdict) = DiagnosticEngine.runRknEchoTest()
                                            diagResult = diagResult.copy(rknStatus = rknState, rknPassRatePercent = passRate, rknVerdict = rknVerdict)

                                            isRunningServices = false
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange),
                                shape = RoundedCornerShape(InstrumentDimens.radiusSmall)
                            ) {
                                Text("Проверить службы")
                            }
                        }

                        DiagnosticServiceRow(
                            title = "1. Telegram (MTProto)",
                            description = "Опрос дата-центров 149.154.167.99 / 91.108.56.165",
                            state = diagResult.tgStatus,
                            metric = if (diagResult.tgPingMs > 0) "${diagResult.tgPingMs} мс" else "—",
                            verdict = diagResult.tgVerdict,
                            icon = Icons.Default.Send
                        )

                        DiagnosticServiceRow(
                            title = "2. YouTube (Видеопоток)",
                            description = "Замер скорости чанков youtubei.googleapis.com",
                            state = diagResult.ytStatus,
                            metric = if (diagResult.ytSpeedMbps > 0) "${diagResult.ytSpeedMbps} Мбит/с" else "—",
                            verdict = diagResult.ytVerdict,
                            icon = Icons.Default.PlayCircle
                        )

                        DiagnosticServiceRow(
                            title = "3. Проходимость DoH и ресурсов",
                            description = "Проверка пула адресов через туннель",
                            state = diagResult.rknStatus,
                            metric = if (diagResult.rknPassRatePercent > 0) "${diagResult.rknPassRatePercent}%" else "—",
                            verdict = diagResult.rknVerdict,
                            icon = Icons.Default.Security
                        )
                    }
                }

                Spacer(modifier = Modifier.height(InstrumentDimens.space16))
            }
        }
    }
}

@Composable
private fun StageCardItem(stage: DiagStageResult) {
    val (statusColor, statusBg, statusText) = when (stage.status) {
        DiagStageStatus.SUCCESS -> Triple(SemanticGreen, SemanticGreenBg, "УСПЕШНО")
        DiagStageStatus.WARNING -> Triple(SemanticAmber, SemanticAmberBg, "ПРЕДУПРЕЖДЕНИЕ")
        DiagStageStatus.ERROR -> Triple(SemanticRed, SemanticRedBg, "ОШИБКА")
        DiagStageStatus.CHECKING -> Triple(SignalOrange, SignalOrangeContainer, "ПРОВЕРЯЕТСЯ")
        DiagStageStatus.WAITING -> Triple(GraphiteTertiary, MineralSurfaceSubtle, "ОЖИДАНИЕ")
        DiagStageStatus.UNTESTED -> Triple(GraphiteTertiary, MineralSurfaceSubtle, "НЕ ПРОВЕРЕНО")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(InstrumentDimens.space12)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stage.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Surface(
                    color = statusBg,
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space4))
            Text(text = stage.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (stage.route.isNotBlank()) {
                Text(text = "Маршрут: ${stage.route} • Метод: ${stage.methodUsed}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = GraphiteTertiary)
            }

            if (stage.errorDetails != null) {
                Spacer(modifier = Modifier.height(InstrumentDimens.space4))
                Text(text = "Ошибка: ${stage.errorDetails}", style = MaterialTheme.typography.bodySmall, color = SemanticRed)
            }
        }
    }
}

@Composable
private fun DiagnosticServiceRow(
    title: String,
    description: String,
    state: DiagnosticResult.TestState,
    metric: String,
    verdict: String,
    icon: ImageVector
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
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(statusBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(InstrumentDimens.space12))
                    Column {
                        Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Surface(color = statusBg, shape = RoundedCornerShape(InstrumentDimens.radiusSmall)) {
                    Text(
                        text = metric,
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            if (verdict.isNotBlank()) {
                Spacer(modifier = Modifier.height(InstrumentDimens.space8))
                Text(text = verdict, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
        }
    }
}
