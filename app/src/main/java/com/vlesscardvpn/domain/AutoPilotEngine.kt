package com.vlesscardvpn.domain

import android.content.Context
import android.util.Log
import com.vlesscardvpn.data.db.AppDatabase
import com.vlesscardvpn.data.db.toDomain
import com.vlesscardvpn.data.db.toEntity
import com.vlesscardvpn.worker.VlessVpnService
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutoPilotEngine(
    private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var healthCheckJob: Job? = null

    private val _state = MutableStateFlow(AutoPilotState())
    val state = _state.asStateFlow()

    private var consecutiveFailures = 0

    fun startAutoPilot(intervalSeconds: Int = 30) {
        _state.value = _state.value.copy(
            isEnabled = true,
            status = AutoPilotStatus.ACTIVE,
            logs = _state.value.logs + "[AUTOPILOT] Started autonomous monitoring cycle (${intervalSeconds}s)"
        )
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive && _state.value.isEnabled) {
                try {
                    performHealthCycle()
                } catch (e: Exception) {
                    Log.e("AutoPilotEngine", "Error in health cycle", e)
                }
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stopAutoPilot() {
        healthCheckJob?.cancel()
        _state.value = _state.value.copy(
            isEnabled = false,
            status = AutoPilotStatus.IDLE,
            logs = _state.value.logs + "[AUTOPILOT] Monitoring stopped"
        )
    }

    suspend fun triggerManualScan() = withContext(Dispatchers.IO) {
        performHealthCycle()
    }

    private suspend fun performHealthCycle() {
        val currentVpnState = VlessVpnService.vpnStats.value
        val dao = database.vlessConfigDao()
        val allEntities = dao.getAll()
        if (allEntities.isEmpty()) return

        _state.value = _state.value.copy(isEvaluating = true)

        // 1. If VPN is connected, test current tunnel end-to-end
        if (currentVpnState.status == VpnStatus.CONNECTED) {
            val (isWorking, latency) = PingTester.verifyEndToEndConnection(timeoutMs = 3000)
            val currentConfig = currentVpnState.activeConfig

            if (!isWorking) {
                consecutiveFailures++
                val msg = "[FAIL] Active tunnel check failed (attempt $consecutiveFailures/3)"
                addLog(msg)

                if (currentConfig != null) {
                    val updated = currentConfig.copy(
                        healthState = "DEGRADED",
                        failureCount = currentConfig.failureCount + 1,
                        lastCheck = System.currentTimeMillis()
                    )
                    dao.update(updated.toEntity())
                }

                if (consecutiveFailures >= 3) {
                    addLog("[FAILOVER] 3 confirmed failures on active node. Triggering failover...")
                    switchToNextBestConfig(allEntities.map { it.toDomain() }, excludeId = currentConfig?.id)
                    consecutiveFailures = 0
                }
            } else {
                consecutiveFailures = 0
                if (currentConfig != null) {
                    val updated = currentConfig.copy(
                        healthState = "HEALTHY",
                        failureCount = 0,
                        lastCheck = System.currentTimeMillis(),
                        httpLatencyMs = latency
                    )
                    dao.update(updated.toEntity())
                }
            }
        } else if (currentVpnState.status == VpnStatus.ERROR || currentVpnState.status == VpnStatus.DISCONNECTED) {
            // If AutoPilot enabled and status is ERROR or AutoConnect is desired, pick the best node
            if (_state.value.isEnabled && currentVpnState.status == VpnStatus.ERROR) {
                addLog("[RECOVERY] Active node in ERROR state. Selecting fastest backup node...")
                switchToNextBestConfig(allEntities.map { it.toDomain() }, excludeId = currentVpnState.activeConfig?.id)
            }
        }

        _state.value = _state.value.copy(
            isEvaluating = false,
            lastScanTime = System.currentTimeMillis()
        )
    }

    private suspend fun switchToNextBestConfig(configs: List<VlessConfig>, excludeId: String?) {
        _state.value = _state.value.copy(status = AutoPilotStatus.SWITCHING)

        val candidates = configs.filter { it.id != excludeId }
        var selectedConfig: VlessConfig? = null
        var bestPing = Int.MAX_VALUE

        for (candidate in candidates.take(15)) {
            val breakdown = PingTester.testDetailedLatency(candidate, timeoutMs = 2500)
            if (breakdown.success) {
                val ping = if (breakdown.tlsMs > 0) breakdown.tlsMs else breakdown.tcpMs
                if (ping in 1 until bestPing) {
                    bestPing = ping
                    selectedConfig = candidate.copy(
                        pingMs = ping,
                        tcpLatencyMs = breakdown.tcpMs,
                        tlsLatencyMs = breakdown.tlsMs,
                        healthState = "HEALTHY",
                        lastCheck = System.currentTimeMillis()
                    )
                }
            }
        }

        if (selectedConfig != null) {
            addLog("[SWITCH] Selected candidate: ${selectedConfig.name} (${bestPing}ms). Initiating reconnect...")
            database.vlessConfigDao().update(selectedConfig.toEntity())
            database.vlessConfigDao().setActive(selectedConfig.id)
            _state.value = _state.value.copy(
                status = AutoPilotStatus.ACTIVE,
                currentBestConfig = selectedConfig
            )
            VlessVpnService.startVpn(context, selectedConfig)
        } else {
            addLog("[WARN] No responsive fallback node discovered. Retrying next cycle.")
            _state.value = _state.value.copy(status = AutoPilotStatus.ACTIVE)
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $message"
        val currentLogs = _state.value.logs.takeLast(40) + formatted
        _state.value = _state.value.copy(logs = currentLogs)
    }

    fun release() {
        healthCheckJob?.cancel()
        scope.cancel()
    }
}
