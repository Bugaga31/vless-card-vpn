package com.vlesscardvpn.domain

enum class AutoPilotStatus {
    IDLE,
    SCANNING,
    SWITCHING,
    ACTIVE,
    ERROR
}

data class AutoPilotState(
    val isEnabled: Boolean = false,
    val status: AutoPilotStatus = AutoPilotStatus.IDLE,
    val currentBestConfig: VlessConfig? = null,
    val lastScanTime: Long = 0L,
    val consecutiveFailures: Int = 0,
    val logs: List<String> = emptyList(),
    val isEvaluating: Boolean = false
)
