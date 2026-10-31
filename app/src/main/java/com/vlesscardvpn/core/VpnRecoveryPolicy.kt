package com.vlesscardvpn.core

object VpnRecoveryPolicy {
    const val RETRY_MS=1000L
    const val RESERVE_RETRY_MS=5000L
    /** Quiet screen may suspend optional work, never Auto's failure detector. */
    fun pauseWatchdog(quiet:Boolean,auto:Boolean)=quiet && !auto
    @Suppress("UNUSED_PARAMETER") fun healthOnly(quiet:Boolean,auto:Boolean)=auto
    fun watchPeriod(requested: Long, auto: Boolean, quiet:Boolean=false) =
        if(auto && quiet) 30000L else if(auto) requested.coerceIn(20000L,60000L) else requested
}
