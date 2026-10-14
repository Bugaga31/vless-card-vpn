package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Optional, process-local session. No OS boost, privilege, routing exclusion or model training. */
object GameSession {
    const val MAX_MS = 60 * 60000L
    val deadline = MutableStateFlow(0L)
    fun valid(until: Long, now: Long) = now >= 0 && until > now && until - now in 1..MAX_MS
    fun active(now: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        val yes = valid(deadline.value, now)
        if (!yes && deadline.value != 0L) deadline.value = 0
        return yes
    }
    fun set(on: Boolean, now: Long = android.os.SystemClock.elapsedRealtime()) { deadline.value = if (on) now + MAX_MS else 0 }
}
