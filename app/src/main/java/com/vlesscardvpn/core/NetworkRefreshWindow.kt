package com.vlesscardvpn.core

/** Coalesce capability bursts without indefinitely postponing an actual physical handoff. */
class NetworkRefreshWindow {
    companion object { const val QUIET_MS = 100L; const val MAX_WAIT_MS = 250L }
    private var firstAt:Long?=null
    @Synchronized fun delayMs(now:Long):Long {
        val first=firstAt?.takeIf {now>=it} ?: now.also {firstAt=it}
        val elapsed=if(now-first<0) MAX_WAIT_MS else now-first
        return minOf(QUIET_MS,(MAX_WAIT_MS-elapsed.coerceAtMost(MAX_WAIT_MS)).coerceAtLeast(0))
    }
    @Synchronized fun reset() {firstAt=null}
}
