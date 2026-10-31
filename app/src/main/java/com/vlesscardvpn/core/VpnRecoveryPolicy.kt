package com.vlesscardvpn.core

object VpnRecoveryPolicy {
    const val RETRY_MS=1000L
    const val RESERVE_RETRY_MS=5000L
    /** Quiet screen may suspend optional work, never Auto's failure detector. */
    fun pauseWatchdog(quiet:Boolean,auto:Boolean)=quiet && !auto
    fun healthOnly(quiet:Boolean,auto:Boolean)=quiet && auto
    fun watchPeriod(requested: Long, auto: Boolean, quiet:Boolean=false) =
        if(auto && quiet) 10000L else if(auto) requested.coerceIn(1000L,5000L) else requested
}
