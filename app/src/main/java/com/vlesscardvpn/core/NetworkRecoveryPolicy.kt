package com.vlesscardvpn.core

/** Restart discovery when the previous network had no proven usable route. */
object NetworkRecoveryPolicy {
    fun restartDiscovery(automatic: Boolean, hasTunnel: Boolean, recheckPassed: Boolean): Boolean =
        automatic && (!hasTunnel || !recheckPassed)
}
