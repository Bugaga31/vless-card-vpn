package com.vlesscardvpn.domain

/** Pure authorization rules; endpoint reachability never grants permission. */
data class AutoPilotPolicy(
    val enabled: Boolean,
    val consentGiven: Boolean,
    val failoverEnabled: Boolean,
    val favoritesOnly: Boolean
) {
    val canMonitor: Boolean get() = enabled && consentGiven
    val canFailover: Boolean get() = canMonitor && failoverEnabled

    fun permits(candidate: VlessConfig, failedId: String?): Boolean =
        canFailover && failedId != null && candidate.id != failedId &&
            (!favoritesOnly || candidate.isFavorite)

    companion object {
        fun sameConnection(
            expectedId: String?, expectedStartedAt: Long,
            currentId: String?, currentStartedAt: Long, connected: Boolean
        ): Boolean = connected && expectedId != null && expectedStartedAt > 0L &&
            expectedId == currentId && expectedStartedAt == currentStartedAt
    }
}
