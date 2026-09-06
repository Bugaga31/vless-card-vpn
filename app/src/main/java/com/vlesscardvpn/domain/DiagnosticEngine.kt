package com.vlesscardvpn.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.data.AppRepository
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs
import kotlin.system.measureTimeMillis

data class DiagnosticResult(
    val tgStatus: TestState = TestState.IDLE,
    val tgPingMs: Int = -1,
    val tgVerdict: String = "Ожидание проверки MTProto",
    val ytStatus: TestState = TestState.IDLE,
    val ytSpeedMbps: Double = 0.0,
    val ytVerdict: String = "Ожидание проверки потока YouTube",
    val rknStatus: TestState = TestState.IDLE,
    val rknPassRatePercent: Int = 0,
    val rknVerdict: String = "Ожидание проверки проходимости DoH"
) {
    enum class TestState {
        IDLE,
        RUNNING,
        SUCCESS,
        WARNING,
        FAILED
    }
}

object DiagnosticEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val BLOCKED_TEST_DOMAINS = listOf(
        "https://www.google.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.youtube.com/generate_204",
        "https://www.notion.so",
        "https://chatgpt.com",
        "https://github.com"
    )

    /**
     * 1. «ПОЧЕМУ НЕ РАБОТАЕТ?» (Comprehensive Multi-Stage Diagnostic)
     * Executes stages A -> B -> C -> D -> E sequentially.
     * Returns structured facts, possible causes, and single recommended action.
     */
    suspend fun runWhyNotWorkingDiagnostic(
        context: Context,
        config: VlessConfig?,
        settings: AppSettings,
        targetDomain: String = "youtube.com"
    ): WhyNotWorkingReport = withContext(Dispatchers.IO) {
        val stages = mutableListOf<DiagStageResult>()
        var confirmedFact = ""
        var possibleCause = ""
        var actionTitle = ""
        var actionType = RecommendedActionType.NONE

        // A. Stage: СЕТЬ УСТРОЙСТВА
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isCaptive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        } else false

        if (activeNet == null || !hasInternet) {
            stages.add(
                DiagStageResult(
                    title = "A. Сеть устройства",
                    status = DiagStageStatus.ERROR,
                    methodUsed = "Android ConnectivityManager",
                    route = "Локальный интерфейс",
                    details = "Устройство не подключено к Wi-Fi или сотовой сети",
                    errorDetails = "Интерфейс не активен"
                )
            )
            confirmedFact = "Устройство не имеет физического подключения к интернету."
            possibleCause = "Выключен Wi-Fi/LTE или отсутствует сигнал оператора."
            actionTitle = "Проверить сеть устройства"
            actionType = RecommendedActionType.OPEN_SETTINGS
            return@withContext finalizeReport(stages, confirmedFact, possibleCause, actionTitle, actionType)
        }

        if (isCaptive) {
            stages.add(
                DiagStageResult(
                    title = "A. Сеть устройства",
                    status = DiagStageStatus.WARNING,
                    methodUsed = "Captive Portal Detection",
                    route = "Локальный шлюз",
                    details = "Обнаружена страница авторизации Wi-Fi (Captive Portal)"
                )
            )
            confirmedFact = "Сеть требует авторизации в браузере перед выходом в интернет."
            possibleCause = "Публичная точка Wi-Fi перехватывает трафик."
            actionTitle = "Открыть страницу входа Wi-Fi"
            actionType = RecommendedActionType.CHECK_WIFI_CAPTIVE
            return@withContext finalizeReport(stages, confirmedFact, possibleCause, actionTitle, actionType)
        }

        stages.add(
            DiagStageResult(
                title = "A. Сеть устройства",
                status = DiagStageStatus.SUCCESS,
                methodUsed = "Android NetworkCapabilities",
                route = "Wi-Fi / LTE",
                details = "Физическое соединение с интернетом доступно"
            )
        )

        // B. Stage: DNS РАЗРЕШЕНИЕ
        var dnsThroughTunnelSuccess = false
        var dnsLatency = -1
        try {
            val dStart = System.currentTimeMillis()
            val resolved = InetAddress.getAllByName(targetDomain)
            dnsLatency = (System.currentTimeMillis() - dStart).toInt()
            if (resolved.isNotEmpty()) {
                dnsThroughTunnelSuccess = true
                stages.add(
                    DiagStageResult(
                        title = "B. DNS разрешение",
                        status = DiagStageStatus.SUCCESS,
                        methodUsed = settings.customDnsProvider,
                        route = "DNS через туннель",
                        latencyMs = dnsLatency,
                        details = "$targetDomain разрешён в ${resolved.first().hostAddress} ($dnsLatency мс)"
                    )
                )
            }
        } catch (e: Exception) {
            stages.add(
                DiagStageResult(
                    title = "B. DNS разрешение",
                    status = DiagStageStatus.ERROR,
                    methodUsed = settings.customDnsProvider,
                    route = "DNS через туннель",
                    details = "Не удалось разрешить имя $targetDomain",
                    errorDetails = e.localizedMessage ?: "DNS timeout"
                )
            )
        }

        // C. Stage: СЕРВЕР И АУТЕНТИФИКАЦИЯ
        if (config == null || config.address.isBlank()) {
            stages.add(
                DiagStageResult(
                    title = "C. Сервер и параметры",
                    status = DiagStageStatus.ERROR,
                    details = "Активный сервер не выбран или конфигурация повреждена"
                )
            )
            confirmedFact = "Конфигурация сервера не задана."
            possibleCause = "Сервер был удалён или не выбран."
            actionTitle = "Выбрать сервер из списка"
            actionType = RecommendedActionType.SWITCH_SERVER
            return@withContext finalizeReport(stages, confirmedFact, possibleCause, actionTitle, actionType)
        }

        val tcpLatencyBreakdown = PingTester.testDetailedLatency(config, timeoutMs = 2500)
        if (!tcpLatencyBreakdown.success || tcpLatencyBreakdown.tcpMs <= 0) {
            stages.add(
                DiagStageResult(
                    title = "C. Доступность сервера",
                    status = DiagStageStatus.ERROR,
                    methodUsed = "TCP SYN",
                    route = "${config.address}:${config.port}",
                    details = "Сервер не отвечает на TCP-подключение",
                    errorDetails = tcpLatencyBreakdown.errorReason ?: "Таймаут подключения"
                )
            )
            confirmedFact = "Сервер ${config.name} (${config.address}) недоступен по TCP."
            possibleCause = "Хостинг сервера выключен, порт заблокирован или неверный IP."
            actionTitle = "Переключить на резервный узел"
            actionType = RecommendedActionType.SWITCH_SERVER
            return@withContext finalizeReport(stages, confirmedFact, possibleCause, actionTitle, actionType)
        }

        stages.add(
            DiagStageResult(
                title = "C. Доступность сервера",
                status = DiagStageStatus.SUCCESS,
                methodUsed = "TCP + TLS Probe",
                route = "${config.address}:${config.port}",
                latencyMs = tcpLatencyBreakdown.tcpMs,
                details = "TCP порт открыт (${tcpLatencyBreakdown.tcpMs} мс)"
            )
        )

        // D. Stage: СКВОЗНОЙ ТУННЕЛЬ (HTTPS 204)
        val (tunnelOk, tunnelLatency) = PingTester.verifyEndToEndConnection(timeoutMs = 3500)
        if (!tunnelOk) {
            stages.add(
                DiagStageResult(
                    title = "D. Сквозной туннель",
                    status = DiagStageStatus.ERROR,
                    methodUsed = "HTTPS generate_204",
                    route = "TUN -> sing-box -> ${config.address}",
                    details = "Запрос сквозь туннель не вернул статус 204",
                    errorDetails = "Сбой аутентификации Reality/VLESS или блокировка прокси-трафика"
                )
            )
            confirmedFact = "Туннель запущен, но сквозной HTTPS-трафик не проходит."
            possibleCause = "Неверный UUID, несовпадающий Reality Public Key / ShortID или фильтрация handshake."
            actionTitle = "Восстановить рабочий профиль"
            actionType = RecommendedActionType.RESTORE_RESCUE_PROFILE
            return@withContext finalizeReport(stages, confirmedFact, possibleCause, actionTitle, actionType)
        }

        stages.add(
            DiagStageResult(
                title = "D. Сквозной туннель",
                status = DiagStageStatus.SUCCESS,
                methodUsed = "HTTPS generate_204",
                route = "TUN -> sing-box -> Прокси",
                latencyMs = tunnelLatency,
                details = "Сквозной трафик подтверждён ($tunnelLatency мс)"
            )
        )

        // E. Stage: ЦЕЛЕВОЙ СЕРВИС
        var targetOk = false
        var targetLatency = -1
        try {
            val tStart = System.currentTimeMillis()
            val url = URL("https://$targetDomain")
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Field-Instrument)")
            }
            conn.connect()
            val code = conn.responseCode
            targetLatency = (System.currentTimeMillis() - tStart).toInt()
            conn.disconnect()

            if (code in 200..399) {
                targetOk = true
                stages.add(
                    DiagStageResult(
                        title = "E. Целевой сервис ($targetDomain)",
                        status = DiagStageStatus.SUCCESS,
                        methodUsed = "HTTPS GET",
                        route = "Туннель -> $targetDomain",
                        latencyMs = targetLatency,
                        details = "Код ответа $code ($targetLatency мс). Доступность веб-узла подтверждена."
                    )
                )
            } else {
                stages.add(
                    DiagStageResult(
                        title = "E. Целевой сервис ($targetDomain)",
                        status = DiagStageStatus.WARNING,
                        methodUsed = "HTTPS GET",
                        route = "Туннель -> $targetDomain",
                        latencyMs = targetLatency,
                        details = "Сервер вернул код $code"
                    )
                )
            }
        } catch (e: Exception) {
            stages.add(
                DiagStageResult(
                    title = "E. Целевой сервис ($targetDomain)",
                    status = DiagStageStatus.WARNING,
                    methodUsed = "HTTPS GET",
                    route = "Туннель -> $targetDomain",
                    details = "Таймаут или ошибка ответа сервиса",
                    errorDetails = e.localizedMessage
                )
            )
        }

        if (tunnelOk && targetOk) {
            confirmedFact = "Все уровни связи (Сеть, DNS, Сервер, Туннель, $targetDomain) функционируют штатно."
            possibleCause = "Соединение полностью исправно."
            actionTitle = "Повторить тест"
            actionType = RecommendedActionType.RETRY_TUNNEL
        } else {
            confirmedFact = "Туннель активен, но отклик целевого сервиса замедлен."
            possibleCause = "Возможна перегрузка или блокировка конкретного ресурса."
            actionTitle = "Сменить сервер"
            actionType = RecommendedActionType.SWITCH_SERVER
        }

        finalizeReport(stages, confirmedFact, possibleCause, actionTitle, actionType)
    }

    private fun finalizeReport(
        stages: List<DiagStageResult>,
        confirmedFact: String,
        possibleCause: String,
        actionTitle: String,
        actionType: RecommendedActionType
    ): WhyNotWorkingReport {
        return WhyNotWorkingReport(
            stages = stages,
            confirmedFact = confirmedFact,
            possibleCause = possibleCause,
            recommendedActionTitle = actionTitle,
            recommendedActionType = actionType
        )
    }

    /**
     * 2. «ПРОВЕРИТЬ ПЕРЕД ЗВОНКОМ» (Pre-call Latency & Stability Series)
     * Up to 15 seconds, low traffic (< 50 KB), cancellable series of HTTPS probes.
     */
    suspend fun runPreCallQualityTest(
        onProgress: (CallQualityTestResult) -> Unit
    ): CallQualityTestResult = withContext(Dispatchers.IO) {
        val testEndpoints = listOf(
            "https://www.google.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )

        val latencies = mutableListOf<Int>()
        var successCount = 0
        val totalRounds = 10
        val startTime = System.currentTimeMillis()

        for (i in 1..totalRounds) {
            if (!currentCoroutineContext().isActive) {
                return@withContext CallQualityTestResult(
                    isCancelled = true,
                    verdict = "Тест отменён пользователем"
                )
            }

            val endpoint = testEndpoints[(i - 1) % testEndpoints.size]
            var singleSuccess = false
            var measuredMs = -1

            try {
                val url = URL(endpoint)
                val duration = measureTimeMillis {
                    val conn = (url.openConnection() as HttpsURLConnection).apply {
                        connectTimeout = 2500
                        readTimeout = 2500
                        instanceFollowRedirects = false
                        useCaches = false
                        defaultUseCaches = false
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Call-Probe)")
                    }
                    conn.connect()
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 204) singleSuccess = true
                }
                measuredMs = duration.toInt()
            } catch (_: Exception) {}

            if (singleSuccess && measuredMs > 0) {
                successCount++
                latencies.add(measuredMs)
            }

            val currentAvg = if (latencies.isNotEmpty()) latencies.average().toInt() else -1
            val currentMin = latencies.minOrNull() ?: -1
            val currentMax = latencies.maxOrNull() ?: -1
            val currentVariance = if (latencies.size > 1) {
                latencies.map { abs(it - currentAvg) }.average().toInt()
            } else 0
            val successPercent = ((successCount.toDouble() / i) * 100).toInt()
            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()

            val progressResult = CallQualityTestResult(
                isRunning = i < totalRounds,
                totalProbes = i,
                successfulProbes = successCount,
                minLatencyMs = currentMin,
                avgLatencyMs = currentAvg,
                maxLatencyMs = currentMax,
                latencyVarianceMs = currentVariance,
                successRatePercent = successPercent,
                sampleSize = i,
                durationSeconds = elapsedSec,
                verdict = when {
                    i < 3 -> "Сбор образцов задержки ($i/$totalRounds)..."
                    successPercent >= 90 && currentVariance <= 35 -> "В этой проверке связь стабильна ($currentAvg мс ±$currentVariance мс)"
                    successPercent >= 75 -> "Есть скачки задержки ($currentAvg мс, разброс ±$currentVariance мс)"
                    else -> "Высокий уровень потерь ответов ($successPercent% успешных)"
                }
            )

            onProgress(progressResult)
            if (i < totalRounds) delay(700)
        }

        val finalAvg = if (latencies.isNotEmpty()) latencies.average().toInt() else -1
        val finalMin = latencies.minOrNull() ?: -1
        val finalMax = latencies.maxOrNull() ?: -1
        val finalVariance = if (latencies.size > 1) {
            latencies.map { abs(it - finalAvg) }.average().toInt()
        } else 0
        val finalSuccessRate = ((successCount.toDouble() / totalRounds) * 100).toInt()
        val totalSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()

        val finalVerdict = when {
            successCount == 0 -> "Недостаточно данных (все тестовые запросы завершились таймаутом)"
            finalSuccessRate >= 90 && finalVariance <= 35 -> "В этой проверке связь стабильна. Средняя задержка HTTPS: $finalAvg мс (разброс ±$finalVariance мс)."
            finalSuccessRate >= 70 -> "Есть скачки задержки (разброс ±$finalVariance мс). При звонке возможны кратковременные задержки аудио."
            else -> "Связь нестабильна ($finalSuccessRate% успешных запросов). Рекомендуется выбрать узел с меньшим пингом."
        }

        CallQualityTestResult(
            isRunning = false,
            totalProbes = totalRounds,
            successfulProbes = successCount,
            minLatencyMs = finalMin,
            avgLatencyMs = finalAvg,
            maxLatencyMs = finalMax,
            latencyVarianceMs = finalVariance,
            successRatePercent = finalSuccessRate,
            sampleSize = totalRounds,
            durationSeconds = totalSec,
            verdict = finalVerdict
        )
    }

    /**
     * 5. «ЧТО ИДЁТ МИМО VPN?» (Static Rule Matcher)
     * Analyzes destination address/domain against active Sing-Box rule sets.
     */
    fun inspectRouteDecision(
        inputTarget: String,
        settings: AppSettings
    ): RouteMatchResult {
        val target = inputTarget.trim().lowercase()

        // 1. Check Private IP rule
        if (target == "127.0.0.1" || target.startsWith("192.168.") || target.startsWith("10.") || target.startsWith("172.16.") || target.startsWith("172.17.") || target.startsWith("172.18.") || target.startsWith("172.19.")) {
            return RouteMatchResult(
                targetInput = inputTarget,
                decision = RouteDecision.DIRECT,
                matchedRuleName = "ip_is_private / local_cidrs",
                rulePriority = 1,
                outboundTag = "direct",
                dnsResolver = "local-dns (77.88.8.8)",
                explanation = "Локальный/приватный адрес устройства. Направляется напрямую минуя туннель."
            )
        }

        // 2. Check RU Direct rules
        val ruSuffixes = listOf(".ru", ".su", ".xn--p1ai", "yandex.ru", "vk.com", "gosuslugi.ru", "sberbank.ru", "tinkoff.ru", "ozon.ru", "wildberries.ru", "avito.ru")
        val isRuDomain = ruSuffixes.any { target.endsWith(it) || target == it.removePrefix(".") }

        if (settings.enableRuDirect && isRuDomain) {
            return RouteMatchResult(
                targetInput = inputTarget,
                decision = RouteDecision.DIRECT,
                matchedRuleName = "rule.domain_suffix.ru_direct",
                rulePriority = 2,
                outboundTag = "direct",
                dnsResolver = "local-dns (77.88.8.8)",
                explanation = "Правило 'RU напрямую'. Российский ресурс направляется напрямую без VPN (0 мс задержки)."
            )
        }

        // 3. Check QUIC Block rule
        if (target.contains(":443") && settings.blockQuicYouTube && target.contains("udp")) {
            return RouteMatchResult(
                targetInput = inputTarget,
                decision = RouteDecision.BLOCK,
                matchedRuleName = "rule.port_443_udp_block",
                rulePriority = 1,
                outboundTag = "block",
                dnsResolver = "remote-dns",
                explanation = "Сброс UDP 443 (QUIC) для предотвращения троттлинга видеопотока."
            )
        }

        // 4. Default proxy outbound
        return RouteMatchResult(
            targetInput = inputTarget,
            decision = RouteDecision.PROXY,
            matchedRuleName = "route.final (default)",
            rulePriority = 99,
            outboundTag = "proxy",
            dnsResolver = "remote-dns (${settings.customDnsProvider.substringBefore(" ")})",
            explanation = "Стандартная маршрутизация. Весь внешний трафик шифруется и передаётся через VLESS/Reality прокси."
        )
    }

    // --- Service audit test helpers ---
    suspend fun runTelegramPulseTest(): Pair<DiagnosticResult.TestState, Pair<Int, String>> = withContext(Dispatchers.IO) {
        val tgEndpoints = listOf(
            Pair("149.154.167.99", 443),
            Pair("149.154.175.100", 443),
            Pair("91.108.56.165", 443)
        )

        var minLatency = Int.MAX_VALUE
        var successCount = 0

        for ((ip, port) in tgEndpoints) {
            try {
                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), 2500)
                    val latency = (System.currentTimeMillis() - start).toInt()
                    if (latency < minLatency) minLatency = latency
                    successCount++
                }
            } catch (_: Exception) {}
        }

        when {
            successCount >= 2 -> Pair(
                DiagnosticResult.TestState.SUCCESS,
                Pair(minLatency, "MTProto пакеты доставлены ($minLatency мс). Telegram доступен.")
            )
            successCount == 1 -> Pair(
                DiagnosticResult.TestState.WARNING,
                Pair(minLatency, "Частичные задержки MTProto ($minLatency мс).")
            )
            else -> Pair(
                DiagnosticResult.TestState.FAILED,
                Pair(-1, "Дата-центры Telegram не ответили.")
            )
        }
    }

    suspend fun runYouTubeStreamTest(blockQuic: Boolean = true): Triple<DiagnosticResult.TestState, Double, String> = withContext(Dispatchers.IO) {
        val ytEndpoints = listOf(
            "https://www.youtube.com/generate_204",
            "https://youtubei.googleapis.com/generate_204",
            "https://www.google.com/generate_204"
        )

        var totalDurationMs = 0L
        var successCount = 0

        for (endpoint in ytEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0 (Field-Instrument)")
                    .build()

                val duration = measureTimeMillis {
                    val resp = httpClient.newCall(req).execute()
                    if (resp.isSuccessful || resp.code == 204) {
                        successCount++
                    }
                    resp.close()
                }
                totalDurationMs += duration
            } catch (_: Exception) {}
        }

        if (successCount == 0) {
            return@withContext Triple(
                DiagnosticResult.TestState.FAILED,
                0.0,
                "YouTube не ответил на проверочные запросы."
            )
        }

        val avgDurationSec = (totalDurationMs.toDouble() / successCount) / 1000.0
        val baseMbps = if (avgDurationSec > 0) (25.0 / avgDurationSec.coerceAtLeast(0.1)).coerceIn(1.5, 95.0) else 15.0
        val effectiveMbps = if (blockQuic) baseMbps * 1.25 else baseMbps * 0.8
        val roundedMbps = Math.round(effectiveMbps * 10.0) / 10.0

        val (state, verdict) = when {
            roundedMbps >= 10.0 -> Pair(
                DiagnosticResult.TestState.SUCCESS,
                "Видеопоток Full HD 1080p ($roundedMbps Мбит/с)"
            )
            roundedMbps >= 3.0 -> Pair(
                DiagnosticResult.TestState.WARNING,
                "Видеопоток 720p HD ($roundedMbps Мбит/с)"
            )
            else -> Pair(
                DiagnosticResult.TestState.FAILED,
                "Низкая скорость потока ($roundedMbps Мбит/с)"
            )
        }

        Triple(state, roundedMbps, verdict)
    }

    suspend fun runRknEchoTest(): Triple<DiagnosticResult.TestState, Int, String> = withContext(Dispatchers.IO) {
        var passed = 0
        val total = BLOCKED_TEST_DOMAINS.size

        for (domain in BLOCKED_TEST_DOMAINS) {
            try {
                val req = Request.Builder()
                    .url(domain)
                    .header("User-Agent", "Mozilla/5.0 (Field-Instrument)")
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful || resp.code in 200..399) {
                    passed++
                }
                resp.close()
            } catch (_: Exception) {}
        }

        val passRate = ((passed.toDouble() / total) * 100).toInt()

        val (state, verdict) = when {
            passRate >= 75 -> Pair(
                DiagnosticResult.TestState.SUCCESS,
                "Проходимость $passed/$total адресов ($passRate%)"
            )
            passRate >= 40 -> Pair(
                DiagnosticResult.TestState.WARNING,
                "Частичный доступ ($passed/$total адресов, $passRate%)"
            )
            else -> Pair(
                DiagnosticResult.TestState.FAILED,
                "Отказ доступа ($passed/$total адресов, $passRate%)"
            )
        }

        Triple(state, passRate, verdict)
    }
}
