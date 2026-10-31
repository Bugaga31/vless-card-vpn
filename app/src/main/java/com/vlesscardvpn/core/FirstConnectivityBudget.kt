package com.vlesscardvpn.core

/** Fail a blackholed first request without shrinking selected-service proof budgets.
 * A timeout remains inconclusive, never a successful route or permanent blacklist.
 */
object FirstConnectivityBudget {
    const val FIRST_HTTP_MS = 2500L
    fun cap(foreground:Boolean, auto:Boolean, maxMs:Int = AutoLatencyPolicy.MAX_MS):Long =
        if(foreground && auto) maxOf(FIRST_HTTP_MS, maxMs.coerceIn(1, AutoLatencyRange.LIMIT).toLong() + 100L) else 0L
    fun remaining(overallMs:Long, capMs:Long):Long =
        if(capMs>0) minOf(overallMs.coerceAtLeast(1),capMs) else overallMs.coerceAtLeast(1)
}
