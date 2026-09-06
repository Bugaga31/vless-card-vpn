package com.vlesscardvpn.domain

import com.vlesscardvpn.core.NetworkType
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
    FAILURE
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
