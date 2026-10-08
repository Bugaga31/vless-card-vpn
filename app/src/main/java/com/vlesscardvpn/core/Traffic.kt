package com.vlesscardvpn.core

import android.net.TrafficStats
import android.os.Process
import com.vlesscardvpn.model.Settings

/**
 * Traffic through the VPN per month (the app's own counters: everything tunnelled passes through its process).
 * Counted from the watchdog and on disconnect; the last 3 months are kept.
 */
object Traffic {
    @Volatile private var last = -1L

    private fun now(): Long = runCatching { TrafficStats.getUidRxBytes(Process.myUid()) + TrafficStats.getUidTxBytes(Process.myUid()) }.getOrDefault(0L)
        .coerceAtLeast(0)

    /** Start of a session: count only what comes after. */
    fun start() { last = now() }

    fun tick() {
        if (last < 0) { start(); return }
        val n = now(); val d = n - last; last = n
        if (d > 0) Store.update { it.copy(settings = add(it.settings, d, month())) }
    }

    fun month(t: Long = System.currentTimeMillis()): String = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(t))

    fun add(s: Settings, bytes: Long, month: String): Settings {
        val m = s.traffic + (month to (s.traffic[month] ?: 0L) + bytes)
        return s.copy(traffic = m.toSortedMap().entries.toList().takeLast(3).associate { it.key to it.value })
    }

    /** Home screen line, or "" when nothing yet; [warn] = over 90 % of the user's monthly limit. */
    fun line(s: Settings): Pair<String, Boolean> {
        val b = s.traffic[month()] ?: return "" to false
        val lim = s.monthLimitGb * (1L shl 30)
        val warn = lim > 0 && b > lim * 9 / 10
        return ("За месяц через VPN: " + Actions.bytes(b) + (if (lim > 0) " из ${s.monthLimitGb} ГБ" else "") + if (warn) " — лимит почти исчерпан" else "") to warn
    }
}
