package com.vlesscardvpn.core

/** Traffic counters are hints, never authority to switch a live route. */
object ReserveScoutPolicy {
    enum class Action { PREPARE, VERIFY, KEEP }
    fun next(suspiciousWindows:Int,warmReserves:Int):Action = when {
        suspiciousWindows>=2->Action.VERIFY
        warmReserves<3->Action.PREPARE
        else->Action.KEEP
    }
}
