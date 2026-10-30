package com.vlesscardvpn.core

object VpnRecoveryPolicy {
    const val RETRY_MS=1000L
    const val RESERVE_RETRY_MS=5000L
    fun watchPeriod(requested: Long, auto: Boolean) = if (auto) requested.coerceIn(1000L,5000L) else requested
}
