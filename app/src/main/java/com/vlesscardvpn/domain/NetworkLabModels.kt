package com.vlesscardvpn.domain

import com.vlesscardvpn.core.NetworkType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit state machine for Network Autopilot.
 */
enum class AutopilotStateStatus {
    DISABLED,      // «Выключен»
    CHECKING,      // «Проверка»
    ADAPTING,      // «Подбор»
    STABLE,        // «Стабильно»
    RECOVERING,    // «Восстановление»
    NEEDS_HELP     // «Нужна помощь»
}

/**
 * Discrete pipeline steps for the "Схема работы" conveyor.
 */
enum class PipelineStep {
    NETWORK,       // 1. Сеть (Интерфейс, тип подключения)
    PROFILE,       // 2. Профиль (MTU, DNS, параметры)
    TUNNEL,        // 3. Туннель (sing-box ядро)
    VERIFICATION   // 4. Проверка (HTTPS 204 сквозь прокси)
}

enum class StepHealth {
    IDLE,
    IN_PROGRESS,
    SUCCESS,
    WARNING,
    FAILURE,
    UNTESTED
}

/**
 * Diagnostic failure classification.
 */
enum class FailureCause {
    NONE,
    NO_INTERNET_CONNECTIVITY,
    CAPTIVE_PORTAL_DETECTED,
    DNS_RESOLUTION_FAILURE,
    PROXY_HANDSHAKE_TIMEOUT,
    AUTH_REJECTED,
    PROBE_TARGET_UNREACHABLE,
    UNKNOWN
}

/**
 * Stage statuses for "Почему не работает?" multi-stage diagnostics.
 */
enum class DiagStageStatus {
    WAITING,       // «Ожидание»
    CHECKING,      // «Проверяется»
    SUCCESS,       // «Успешно»
    WARNING,       // «Предупреждение»
    ERROR,         // «Ошибка»
    UNTESTED       // «Не проверено»
}

/**
 * Stage result for "Почему не работает?"
 */
data class DiagStageResult(
    val title: String,
    val status: DiagStageStatus = DiagStageStatus.WAITING,
    val methodUsed: String = "",
    val route: String = "",
    val latencyMs: Int = -1,
    val details: String = "",
    val errorDetails: String? = null
)

/**
 * Comprehensive "Почему не работает?" result with confirmed facts, possible causes, and single recommended action.
 */
data class WhyNotWorkingReport(
    val stages: List<DiagStageResult> = emptyList(),
    val confirmedFact: String = "",
    val possibleCause: String = "",
    val recommendedActionTitle: String = "",
    val recommendedActionType: RecommendedActionType = RecommendedActionType.NONE,
    val timestamp: Long = System.currentTimeMillis()
)

enum class RecommendedActionType {
    NONE,
    RETRY_TUNNEL,
    SWITCH_SERVER,
    RESTORE_RESCUE_PROFILE,
    CHECK_WIFI_CAPTIVE,
    TOGGLE_RU_DIRECT,
    OPEN_SETTINGS
}

/**
 * Pre-call test measurement results («Проверить перед звонком»).
 */
data class CallQualityTestResult(
    val isRunning: Boolean = false,
    val totalProbes: Int = 0,
    val successfulProbes: Int = 0,
    val minLatencyMs: Int = -1,
    val avgLatencyMs: Int = -1,
    val maxLatencyMs: Int = -1,
    val latencyVarianceMs: Int = 0, // Honest spread/variance, not fake jitter
    val successRatePercent: Int = 0,
    val sampleSize: Int = 0,
    val durationSeconds: Int = 0,
    val verdict: String = "Тест готов к запуску",
    val limitationNote: String = "Методика: серия HTTPS-запросов через прокси (лимит < 50 КБ, до 15 сек). Не является гарантией реального аудио/видеокодека звонка.",
    val isCancelled: Boolean = false
)

/**
 * Persisted Rescue Profile snapshot («Спасательный профиль»).
 */
data class RescueProfile(
    val id: String,
    val configId: String,
    val serverName: String,
    val serverAddress: String,
    val serverPort: Int,
    val protocolType: String,
    val optimalMtu: Int,
    val effectiveDns: String,
    val blockQuic: Boolean,
    val enableRuDirect: Boolean,
    val customSni: String,
    val verifiedLatencyMs: Int,
    val verifiedTimestamp: Long,
    val isManualBookmark: Boolean = false,
    val coreVersion: String = "sing-box 1.13-mod",
    val configSchemaVersion: Int = 1
) {
    fun isExpired(ttlMillis: Long = 7 * 24 * 3600 * 1000L): Boolean {
        return (System.currentTimeMillis() - verifiedTimestamp) > ttlMillis
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("id", id)
            put("configId", configId)
            put("serverName", serverName)
            put("serverAddress", serverAddress)
            put("serverPort", serverPort)
            put("protocolType", protocolType)
            put("optimalMtu", optimalMtu)
            put("effectiveDns", effectiveDns)
            put("blockQuic", blockQuic)
            put("enableRuDirect", enableRuDirect)
            put("customSni", customSni)
            put("verifiedLatencyMs", verifiedLatencyMs)
            put("verifiedTimestamp", verifiedTimestamp)
            put("isManualBookmark", isManualBookmark)
            put("coreVersion", coreVersion)
            put("configSchemaVersion", configSchemaVersion)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): RescueProfile? {
            return try {
                val obj = JSONObject(jsonStr)
                RescueProfile(
                    id = obj.optString("id", obj.optString("configId", "")),
                    configId = obj.getString("configId"),
                    serverName = obj.optString("serverName", "Сервер"),
                    serverAddress = obj.optString("serverAddress", ""),
                    serverPort = obj.optInt("serverPort", 443),
                    protocolType = obj.optString("protocolType", "vless"),
                    optimalMtu = obj.optInt("optimalMtu", 1400),
                    effectiveDns = obj.optString("effectiveDns", "https://1.1.1.1/dns-query"),
                    blockQuic = obj.optBoolean("blockQuic", true),
                    enableRuDirect = obj.optBoolean("enableRuDirect", true),
                    customSni = obj.optString("customSni", "auto"),
                    verifiedLatencyMs = obj.optInt("verifiedLatencyMs", -1),
                    verifiedTimestamp = obj.optLong("verifiedTimestamp", System.currentTimeMillis()),
                    isManualBookmark = obj.optBoolean("isManualBookmark", false),
                    coreVersion = obj.optString("coreVersion", "sing-box 1.13-mod"),
                    configSchemaVersion = obj.optInt("configSchemaVersion", 1)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Server Passport («Паспорт сервера»).
 */
data class ServerPassport(
    val configId: String,
    val serverName: String,
    val serverAddress: String,
    val serverPort: Int,
    val protocolAndTransport: String,
    val sourceOrigin: String,
    val addedDateFormatted: String,
    val subscriptionExpiryFormatted: String,
    val signatureVerificationState: String,
    val checkHistorySummary: String, // e.g., "Успешно 18 из 20 проверок"
    val recentChecks: List<PassportCheckRecord> = emptyList(),
    val recentRecoveries: List<String> = emptyList()
)

data class PassportCheckRecord(
    val timestamp: Long,
    val networkType: String,
    val latencyMs: Int,
    val isSuccess: Boolean,
    val method: String
)

/**
 * Routing inspection result («Что идёт мимо VPN?»).
 */
enum class RouteDecision {
    PROXY,   // Через VPN
    DIRECT,  // Напрямую
    BLOCK,   // Заблокировано
    UNKNOWN  // Маршрут не определён
}

data class RouteMatchResult(
    val targetInput: String,
    val decision: RouteDecision,
    val matchedRuleName: String,
    val rulePriority: Int,
    val outboundTag: String,
    val dnsResolver: String,
    val explanation: String
)

/**
 * Persisted profile snapshot for a specific server and network context.
 */
data class SavedWorkingProfile(
    val configId: String,
    val networkType: String,
    val optimalMtu: Int,
    val effectiveDns: String,
    val blockQuic: Boolean,
    val verifiedLatencyMs: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(ttlMillis: Long = 7 * 24 * 3600 * 1000L): Boolean {
        return (System.currentTimeMillis() - timestamp) > ttlMillis
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("configId", configId)
            put("networkType", networkType)
            put("optimalMtu", optimalMtu)
            put("effectiveDns", effectiveDns)
            put("blockQuic", blockQuic)
            put("verifiedLatencyMs", verifiedLatencyMs)
            put("timestamp", timestamp)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SavedWorkingProfile? {
            return try {
                val obj = JSONObject(jsonStr)
                SavedWorkingProfile(
                    configId = obj.getString("configId"),
                    networkType = obj.optString("networkType", "UNKNOWN"),
                    optimalMtu = obj.optInt("optimalMtu", 1400),
                    effectiveDns = obj.optString("effectiveDns", "https://1.1.1.1/dns-query"),
                    blockQuic = obj.optBoolean("blockQuic", true),
                    verifiedLatencyMs = obj.optInt("verifiedLatencyMs", -1),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Honest telemetry and diagnostic state of Network Autopilot.
 */
data class NetworkAutopilotState(
    val status: AutopilotStateStatus = AutopilotStateStatus.DISABLED,
    val isEnabled: Boolean = false,
    val consentGiven: Boolean = false,
    val currentStep: PipelineStep = PipelineStep.NETWORK,
    val stepHealthMap: Map<PipelineStep, StepHealth> = mapOf(
        PipelineStep.NETWORK to StepHealth.IDLE,
        PipelineStep.PROFILE to StepHealth.IDLE,
        PipelineStep.TUNNEL to StepHealth.IDLE,
        PipelineStep.VERIFICATION to StepHealth.IDLE
    ),
    val activeConfig: VlessConfig? = null,
    val currentMtu: Int = 1400,
    val currentDns: String = "https://1.1.1.1/dns-query",
    val isQuicBlocked: Boolean = true,
    
    // Honest telemetry metrics (no fake jitter or speed calculations)
    val checkSuccessRatePercent: Int = 100,
    val probeCountTotal: Int = 0,
    val probeCountSuccess: Int = 0,
    val lastHttpsLatencyMs: Int = -1,
    val latencyVarianceMs: Int = 0,
    val reconnectCount: Int = 0,
    val consecutiveFailures: Int = 0,
    
    // Explanations & Diagnosis
    val lastSelectionReason: String = "Начальный профиль конфигурации",
    val lastChangeExplanation: String = "Автопилот ожидает запуска",
    val failureCause: FailureCause = FailureCause.NONE,
    val lastCheckTimestamp: Long = 0L,
    val isBusy: Boolean = false,
    
    // History log
    val eventLogs: List<String> = emptyList()
)
