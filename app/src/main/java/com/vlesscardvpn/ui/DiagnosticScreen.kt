package com.vlesscardvpn.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.DiagnosticEngine
import com.vlesscardvpn.domain.DiagnosticResult
import com.vlesscardvpn.ui.components.CyberParticleBackground
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

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
                "💡 Рекомендация: ТСПУ блокирует MTProto. Включите профиль 'Яндекс' или 'ВКонтакте' с TLS 1.3 Vision."
            diagResult.ytStatus == DiagnosticResult.TestState.FAILED || (diagResult.ytSpeedMbps in 0.1..4.0) ->
                "💡 Рекомендация: YouTube троттлится. Включите 'Блок QUIC' и уменьшите MTU до 1380."
            diagResult.rknPassRatePercent in 1..60 ->
                "💡 Рекомендация: Часть трафика перехвачена. Увеличьте 'Фрактальное дробление' до 7+."
            diagResult.tgStatus == DiagnosticResult.TestState.SUCCESS && diagResult.ytSpeedMbps >= 10.0 ->
                "⚡ Отлично: Туннель чистый, обход ТСПУ 100%. Скорость оптимальна для 4K."
            else -> "⚡ Запустите проверку для получения адаптивных рекомендаций по обходу блокировок."
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CyberParticleBackground(particleCount = 20)

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "ДИАГНОСТИКА ТУННЕЛЯ",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            color = TextPrimary,
                            fontSize = 17.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
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
                                            tgStatus = tgState,
                                            tgPingMs = tgData.first,
                                            tgVerdict = tgData.second,
                                            ytStatus = ytState,
                                            ytSpeedMbps = ytMbps,
                                            ytVerdict = ytVerdict
                                        )

                                        // 3. RKN Echo
                                        val (rknState, passRate, rknVerdict) = DiagnosticEngine.runRknEchoTest()
                                        diagResult = diagResult.copy(
                                            tgStatus = tgState,
                                            tgPingMs = tgData.first,
                                            tgVerdict = tgData.second,
                                            ytStatus = ytState,
                                            ytSpeedMbps = ytMbps,
                                            ytVerdict = ytVerdict,
                                            rknStatus = rknState,
                                            rknPassRatePercent = passRate,
                                            rknVerdict = rknVerdict
                                        )

                                        isRunningAll = false
                                        snackbarHostState.showSnackbar("Комплексная диагностика завершена")
                                    }
                                }
                            },
                            enabled = !isRunningAll,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF090A0F)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp).height(36.dp)
                        ) {
                            if (isRunningAll) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF090A0F), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Тест всех", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface.copy(alpha = 0.85f))
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // Live Radar Simulation Map
                DiagnosticRadarView(diagResult = diagResult)

                Spacer(modifier = Modifier.height(14.dp))

                // Adaptive Recommendation Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.85f)),
                    border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = NeonPurple.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, NeonPurple)
                        ) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                tint = NeonPurple,
                                modifier = Modifier.padding(8.dp).size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = adaptiveRecommendation,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 1. Telegram Pulse Test Card
                DiagnosticItemCard(
                    title = "1. Telegram-Импульс (MTProto)",
                    subtitle = "Проверка фейковых MTProto пакетов к ДЦ Telegram (149.154.167.99)",
                    state = diagResult.tgStatus,
                    metric = if (diagResult.tgPingMs > 0) "${diagResult.tgPingMs} ms" else "--",
                    verdict = diagResult.tgVerdict,
                    icon = Icons.Default.Send,
                    onRunSingle = {
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

                Spacer(modifier = Modifier.height(12.dp))

                // 2. YouTube Stream Test Card
                DiagnosticItemCard(
                    title = "2. YouTube-Поток (QUIC & HTTP/3)",
                    subtitle = "Имитация плейлиста youtubei.googleapis.com без троттлинга",
                    state = diagResult.ytStatus,
                    metric = if (diagResult.ytSpeedMbps > 0) "${diagResult.ytSpeedMbps} Mbps" else "--",
                    verdict = diagResult.ytVerdict,
                    icon = Icons.Default.VideoLibrary,
                    onRunSingle = {
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

                Spacer(modifier = Modifier.height(12.dp))

                // 3. RKN Echo Test Card
                DiagnosticItemCard(
                    title = "3. Роскомнадзор-Эхо (ТСПУ Bypass)",
                    subtitle = "Тестирование сквозной проходимости заблокированных ресурсов",
                    state = diagResult.rknStatus,
                    metric = if (diagResult.rknPassRatePercent > 0) "${diagResult.rknPassRatePercent}%" else "--",
                    verdict = diagResult.rknVerdict,
                    icon = Icons.Default.Security,
                    onRunSingle = {
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
}

@Composable
private fun DiagnosticItemCard(
    title: String,
    subtitle: String,
    state: DiagnosticResult.TestState,
    metric: String,
    verdict: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRunSingle: () -> Unit
) {
    val stateColor = when (state) {
        DiagnosticResult.TestState.SUCCESS -> NeonGreen
        DiagnosticResult.TestState.WARNING -> NeonAmber
        DiagnosticResult.TestState.FAILED -> NeonRed
        DiagnosticResult.TestState.RUNNING -> NeonCyan
        DiagnosticResult.TestState.IDLE -> TextTertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = stateColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.4f))
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = stateColor,
                            modifier = Modifier.padding(7.dp).size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = title, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                        Text(text = subtitle, fontSize = 11.sp, color = TextSecondary)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = stateColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, stateColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = metric,
                        color = stateColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = verdict,
                    fontSize = 12.sp,
                    color = stateColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRunSingle,
                    enabled = state != DiagnosticResult.TestState.RUNNING,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = NeonCyan),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    if (state == DiagnosticResult.TestState.RUNNING) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), color = NeonCyan, strokeWidth = 2.dp)
                    } else {
                        Text("Тест", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRadarView(diagResult: DiagnosticResult) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarAnim")
    val radarAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarSweep"
    )

    Card(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.85f)),
        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val radius = size.height * 0.42f

                // Radar concentric rings
                drawCircle(color = NeonCyan.copy(alpha = 0.1f), radius = radius, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
                drawCircle(color = NeonCyan.copy(alpha = 0.15f), radius = radius * 0.65f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
                drawCircle(color = NeonPurple.copy(alpha = 0.15f), radius = radius * 0.3f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

                // Center Origin Point (User)
                drawCircle(color = NeonCyan, radius = 4.dp.toPx(), center = Offset(cx, cy))

                // Target Points: Telegram (Left-Top), YouTube (Right-Top), RKN/DoH (Bottom-Center)
                val tgPos = Offset(cx - radius * 0.65f, cy - radius * 0.45f)
                val ytPos = Offset(cx + radius * 0.65f, cy - radius * 0.45f)
                val rknPos = Offset(cx, cy + radius * 0.7f)

                // Draw Beams from Center
                drawLine(
                    color = when (diagResult.tgStatus) {
                        DiagnosticResult.TestState.SUCCESS -> NeonGreen
                        DiagnosticResult.TestState.WARNING -> NeonAmber
                        DiagnosticResult.TestState.FAILED -> NeonRed
                        else -> NeonCyan.copy(alpha = 0.4f)
                    },
                    start = Offset(cx, cy),
                    end = tgPos,
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = when (diagResult.ytStatus) {
                        DiagnosticResult.TestState.SUCCESS -> NeonGreen
                        DiagnosticResult.TestState.WARNING -> NeonAmber
                        DiagnosticResult.TestState.FAILED -> NeonRed
                        else -> NeonPurple.copy(alpha = 0.4f)
                    },
                    start = Offset(cx, cy),
                    end = ytPos,
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = when (diagResult.rknStatus) {
                        DiagnosticResult.TestState.SUCCESS -> NeonGreen
                        DiagnosticResult.TestState.WARNING -> NeonAmber
                        DiagnosticResult.TestState.FAILED -> NeonRed
                        else -> DarkBorder
                    },
                    start = Offset(cx, cy),
                    end = rknPos,
                    strokeWidth = 2.dp.toPx()
                )

                // Nodes
                drawCircle(color = NeonCyan, radius = 5.dp.toPx(), center = tgPos)
                drawCircle(color = NeonPurple, radius = 5.dp.toPx(), center = ytPos)
                drawCircle(color = NeonGreen, radius = 5.dp.toPx(), center = rknPos)
            }

            // Overlay Labels
            Row(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text("TG DC", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("YOUTUBE CDN", color = NeonPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text("RKN / DOH TEST", color = NeonGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
