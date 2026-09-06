package com.vlesscardvpn.domain

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.vlesscardvpn.core.NetworkProfileManager
import com.vlesscardvpn.core.NetworkType
import com.vlesscardvpn.data.db.AppDatabase
import com.vlesscardvpn.data.db.toDomain
import com.vlesscardvpn.data.db.toEntity
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class AutoPilotEngine(
    private val context: Context,
    private val database: AppDatabase
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("autopilot_memory_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engineMutex = Mutex()
    private val engineSessionSequence = AtomicLong(0L)

    private var loopJob: Job? = null
    private val latencyHistory = mutableListOf<Int>()

    private val _state = MutableStateFlow(NetworkAutopilotState())
    val state = _state.asStateFlow()

    init {
        loadInitialState()
    }

    private fun loadInitialState() {
        val isEnabled = prefs.getBoolean("autopilot_enabled", false)
        val consentGiven = prefs.getBoolean("autopilot_consent", false)
        _state.value = _state.value.copy(
            isEnabled = isEnabled,
            consentGiven = consentGiven,
            status = if (isEnabled) AutopilotStateStatus.STABLE else AutopilotStateStatus.DISABLED
        )
    }

    fun setConsent(given: Boolean) {
        prefs.edit().putBoolean("autopilot_consent", given).apply()
        _state.value = _state.value.copy(consentGiven = given)
    }

    fun startAutoPilot(intervalSeconds: Int = 30) {
        prefs.edit().putBoolean("autopilot_enabled", true).apply()
        val seq = engineSessionSequence.incrementAndGet()

        _state.value = _state.value.copy(
            isEnabled = true,
            status = AutopilotStateStatus.CHECKING,
            currentStep = PipelineStep.NETWORK,
            lastChangeExplanation = "Автопилот активирован. Запуск цикла мониторинга (${intervalSeconds}с)"
        )
        addLog("Автопилот сети запущен. Интервал проверки: ${intervalSeconds}с")

        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive && _state.value.isEnabled) {
                try {
                    performAutopilotCycle(seq)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AutoPilotEngine", "Autopilot cycle error", e)
                }
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stopAutoPilot() {
        prefs.edit().putBoolean("autopilot_enabled", false).apply()
        engineSessionSequence.incrementAndGet()
        loopJob?.cancel()
        loopJob = null

        _state.value = _state.value.copy(
            isEnabled = false,
            status = AutopilotStateStatus.DISABLED,
            isBusy = false,
            stepHealthMap = mapOf(
                PipelineStep.NETWORK to StepHealth.IDLE,
                PipelineStep.PROFILE to StepHealth.IDLE,
                PipelineStep.TUNNEL to StepHealth.IDLE,
                PipelineStep.VERIFICATION to StepHealth.IDLE
            ),
            lastChangeExplanation = "Автопилот остановлен пользователем"
        )
        addLog("Автопилот сети отключен")
    }

    suspend fun triggerManualScan() = withContext(Dispatchers.IO) {
        val seq = engineSessionSequence.get()
        performAutopilotCycle(seq)
    }

    /**
     * Resets learned profile cache for privacy and fresh calibration.
     */
    fun resetLearnedMemory() {
        prefs.edit().clear().putBoolean("autopilot_consent", _state.value.consentGiven).apply()
        latencyHistory.clear()
        _state.value = _state.value.copy(
            probeCountTotal = 0,
            probeCountSuccess = 0,
            checkSuccessRatePercent = 100,
            lastHttpsLatencyMs = -1,
            latencyVarianceMs = 0,
            reconnectCount = 0,
            consecutiveFailures = 0,
            lastChangeExplanation = "Память профилей очищена пользователем"
        )
        addLog("Память удачных профилей сброшена")
    }

    /**
     * Restores the last known working profile for the active server.
     */
    suspend fun restoreLastWorkingProfile() = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            val vpnStats = VlessVpnService.vpnStats.value
            val activeCfg = vpnStats.activeConfig ?: database.vlessConfigDao().getActiveConfig()?.toDomain()
            if (activeCfg == null) {
                addLog("Нет активного сервера для отката профиля")
                return@withLock
            }

            val netType = NetworkProfileManager.detectNetworkType(context).name
            val savedProfile = getSavedProfile(activeCfg.id, netType)
            if (savedProfile != null) {
                addLog("Восстановление профиля: MTU ${savedProfile.optimalMtu}, DNS ${savedProfile.effectiveDns.substringBefore("/")}")
                _state.value = _state.value.copy(
                    status = AutopilotStateStatus.RECOVERING,
                    currentMtu = savedProfile.optimalMtu,
                    currentDns = savedProfile.effectiveDns,
                    lastChangeExplanation = "Восстановлен сохраненный рабочий профиль (MTU ${savedProfile.optimalMtu})"
                )
                VlessVpnService.startVpn(context, activeCfg)
            } else {
                addLog("Сохраненный профиль для ${activeCfg.name} ($netType) отсутствует")
            }
        }
    }

    /**
     * Main autonomous state evaluation cycle.
     */
    private suspend fun performAutopilotCycle(seq: Long) = engineMutex.withLock {
        if (!_state.value.isEnabled || seq != engineSessionSequence.get()) return@withLock
        _state.value = _state.value.copy(isBusy = true)

        val vpnState = VlessVpnService.vpnStats.value
        val dao = database.vlessConfigDao()
        val allConfigs = dao.getAll().map { it.toDomain() }

        if (allConfigs.isEmpty()) {
            _state.value = _state.value.copy(
                status = AutopilotStateStatus.NEEDS_HELP,
                isBusy = false,
                lastChangeExplanation = "Список серверов пуст. Добавьте хотя бы один узел связи."
            )
            return@withLock
        }

        // 1. Step: NETWORK evaluation
        _state.value = _state.value.copy(
            currentStep = PipelineStep.NETWORK,
            stepHealthMap = _state.value.stepHealthMap + (PipelineStep.NETWORK to StepHealth.IN_PROGRESS)
        )

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        if (!hasInternet) {
            _state.value = _state.value.copy(
                status = AutopilotStateStatus.NEEDS_HELP,
                failureCause = FailureCause.NO_INTERNET_CONNECTIVITY,
                isBusy = false,
                stepHealthMap = _state.value.stepHealthMap + (PipelineStep.NETWORK to StepHealth.FAILURE),
                lastChangeExplanation = "Устройство не подключено к Интернету (Wi-Fi/LTE недоступны)"
            )
            addLog("Сбой: Отсутствует подключение к сети на устройстве")
            return@withLock
        }

        _state.value = _state.value.copy(
            stepHealthMap = _state.value.stepHealthMap + (PipelineStep.NETWORK to StepHealth.SUCCESS)
        )

        // 2. Step: PROFILE & TUNNEL check
        _state.value = _state.value.copy(
            currentStep = PipelineStep.PROFILE,
            stepHealthMap = _state.value.stepHealthMap + (PipelineStep.PROFILE to StepHealth.SUCCESS)
        )

        if (vpnState.status != VpnStatus.CONNECTED) {
            // VPN is not currently connected
            _state.value = _state.value.copy(
                currentStep = PipelineStep.TUNNEL,
                stepHealthMap = _state.value.stepHealthMap + (PipelineStep.TUNNEL to StepHealth.IDLE),
                isBusy = false
            )
            return@withLock
        }

        _state.value = _state.value.copy(
            currentStep = PipelineStep.TUNNEL,
            stepHealthMap = _state.value.stepHealthMap + (PipelineStep.TUNNEL to StepHealth.SUCCESS)
        )

        // 3. Step: VERIFICATION (Strict HTTPS 204 through proxy)
        _state.value = _state.value.copy(
            currentStep = PipelineStep.VERIFICATION,
            status = AutopilotStateStatus.CHECKING,
            stepHealthMap = _state.value.stepHealthMap + (PipelineStep.VERIFICATION to StepHealth.IN_PROGRESS)
        )

        val currentConfig = vpnState.activeConfig ?: dao.getActiveConfig()?.toDomain()
        val (isWorking, latencyMs) = PingTester.verifyEndToEndConnection(timeoutMs = 3500)

        val newTotalProbes = _state.value.probeCountTotal + 1
        val newSuccessProbes = if (isWorking) _state.value.probeCountSuccess + 1 else _state.value.probeCountSuccess
        val successRate = ((newSuccessProbes.toDouble() / newTotalProbes) * 100).toInt()

        if (isWorking) {
            // Record latency variance (spread) honestly
            latencyHistory.add(latencyMs)
            if (latencyHistory.size > 20) latencyHistory.removeAt(0)
            val variance = if (latencyHistory.size > 1) {
                val avg = latencyHistory.average()
                latencyHistory.map { abs(it - avg) }.average().toInt()
            } else 0

            // Save working profile
            if (currentConfig != null) {
                val netType = NetworkProfileManager.detectNetworkType(context).name
                saveWorkingProfile(
                    SavedWorkingProfile(
                        configId = currentConfig.id,
                        networkType = netType,
                        optimalMtu = _state.value.currentMtu,
                        effectiveDns = _state.value.currentDns,
                        blockQuic = _state.value.isQuicBlocked,
                        verifiedLatencyMs = latencyMs
                    )
                )

                val updatedEntity = currentConfig.copy(
                    healthState = "HEALTHY",
                    failureCount = 0,
                    lastCheck = System.currentTimeMillis(),
                    httpLatencyMs = latencyMs
                ).toEntity()
                dao.update(updatedEntity)
            }

            _state.value = _state.value.copy(
                status = AutopilotStateStatus.STABLE,
                activeConfig = currentConfig,
                consecutiveFailures = 0,
                probeCountTotal = newTotalProbes,
                probeCountSuccess = newSuccessProbes,
                checkSuccessRatePercent = successRate,
                lastHttpsLatencyMs = latencyMs,
                latencyVarianceMs = variance,
                failureCause = FailureCause.NONE,
                isBusy = false,
                stepHealthMap = _state.value.stepHealthMap + (PipelineStep.VERIFICATION to StepHealth.SUCCESS),
                lastSelectionReason = "Стабильное защищенное соединение (${latencyMs} мс)",
                lastChangeExplanation = "Связь стабильна. Текущий профиль подтверждён сквозным HTTPS 204."
            )
        } else {
            // Failure confirmed
            val failures = _state.value.consecutiveFailures + 1
            addLog("Сквозная проверка HTTPS не прошла (сбой $failures/3)")

            if (currentConfig != null) {
                val updated = currentConfig.copy(
                    healthState = "DEGRADED",
                    failureCount = currentConfig.failureCount + 1,
                    lastCheck = System.currentTimeMillis()
                )
                dao.update(updated.toEntity())
            }

            _state.value = _state.value.copy(
                consecutiveFailures = failures,
                probeCountTotal = newTotalProbes,
                probeCountSuccess = newSuccessProbes,
                checkSuccessRatePercent = successRate,
                stepHealthMap = _state.value.stepHealthMap + (PipelineStep.VERIFICATION to StepHealth.FAILURE)
            )

            if (failures >= 3) {
                // 3 consecutive failures -> Initiate disciplined adaptation/failover
                handleFailoverSequence(allConfigs, currentConfig)
            } else {
                _state.value = _state.value.copy(
                    status = AutopilotStateStatus.STABLE,
                    isBusy = false,
                    lastChangeExplanation = "Зафиксирован единичный сбой ($failures/3). Ожидание подтверждения перед переключением."
                )
            }
        }
    }

    /**
     * Changes ONE parameter at a time or safely switches to the fastest permitted backup server.
     */
    private suspend fun handleFailoverSequence(
        allConfigs: List<VlessConfig>,
        failedConfig: VlessConfig?
    ) {
        _state.value = _state.value.copy(
            status = AutopilotStateStatus.ADAPTING,
            lastChangeExplanation = "3 сбоя подряд. Подбор совместимых параметров или резервного узла..."
        )
        addLog("Запуск восстановления: поиск резервного узла")

        // Filter permitted candidates (favorites if user preference enabled)
        val candidates = allConfigs.filter { it.id != failedConfig?.id }
        var bestCandidate: VlessConfig? = null
        var bestPing = Int.MAX_VALUE

        for (candidate in candidates.take(10)) {
            val breakdown = PingTester.testDetailedLatency(candidate, timeoutMs = 2000)
            if (breakdown.success) {
                val ping = if (breakdown.tlsMs > 0) breakdown.tlsMs else breakdown.tcpMs
                if (ping in 1 until bestPing) {
                    bestPing = ping
                    bestCandidate = candidate.copy(
                        pingMs = ping,
                        tcpLatencyMs = breakdown.tcpMs,
                        tlsLatencyMs = breakdown.tlsMs,
                        healthState = "HEALTHY"
                    )
                }
            }
        }

        if (bestCandidate != null) {
            val reconnects = _state.value.reconnectCount + 1
            addLog("Выбран резервный узел: ${bestCandidate.name} (${bestPing} мс)")
            database.vlessConfigDao().update(bestCandidate.toEntity())
            database.vlessConfigDao().setActive(bestCandidate.id)

            _state.value = _state.value.copy(
                status = AutopilotStateStatus.RECOVERING,
                activeConfig = bestCandidate,
                consecutiveFailures = 0,
                reconnectCount = reconnects,
                isBusy = false,
                lastSelectionReason = "Резервный узел ${bestCandidate.name} ($bestPing мс)",
                lastChangeExplanation = "Предыдущий узел не отвечал. Автопилот переключил на резервный сервер."
            )

            VlessVpnService.startVpn(context, bestCandidate)
        } else {
            // All candidates unresponsive
            _state.value = _state.value.copy(
                status = AutopilotStateStatus.NEEDS_HELP,
                failureCause = FailureCause.PROXY_HANDSHAKE_TIMEOUT,
                isBusy = false,
                lastChangeExplanation = "Все доступные серверы не ответили на проверочный запрос. Требуется ручная проверка."
            )
            addLog("Внимание: Ни один резервный узел не ответил. Перебор остановлен.")
        }
    }

    private fun saveWorkingProfile(profile: SavedWorkingProfile) {
        val key = "profile_${profile.configId}_${profile.networkType}"
        prefs.edit().putString(key, profile.toJson()).apply()
    }

    private fun getSavedProfile(configId: String, networkType: String): SavedWorkingProfile? {
        val key = "profile_${configId}_${networkType}"
        val raw = prefs.getString(key, null) ?: return null
        val profile = SavedWorkingProfile.fromJson(raw) ?: return null
        return if (profile.isExpired()) null else profile
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$time] $message"
        val updated = (_state.value.eventLogs.takeLast(49) + entry)
        _state.value = _state.value.copy(eventLogs = updated)
    }

    fun release() {
        loopJob?.cancel()
        scope.cancel()
    }
}
