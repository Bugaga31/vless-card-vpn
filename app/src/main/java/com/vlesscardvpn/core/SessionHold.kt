package com.vlesscardvpn.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** User-requested pause of optional improvements, never of emergency repair or the VPN itself. */
object SessionHold {
    val until = MutableStateFlow(0L)
    fun active(): Boolean = until.value > SystemClock.elapsedRealtime()
    fun start(minutes:Int=30) { until.value = SystemClock.elapsedRealtime() + minutes.coerceIn(1,120) * 60_000L }
    fun clear() { until.value = 0L }
    fun minutesLeft(): Long = ((until.value-SystemClock.elapsedRealtime()).coerceAtLeast(0L)+59_999L)/60_000L
}
