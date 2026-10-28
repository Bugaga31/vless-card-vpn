package com.vlesscardvpn.core

import android.net.TrafficStats
import android.os.Process
import com.vlesscardvpn.model.Settings

/**
 * Process UID byte estimates per month, including probes/updates. Not operator billing or foreign-traffic accounting.
 * Counted from the watchdog and on disconnect; the last 3 months are kept.
 */
object Traffic {
    @Volatile private var last:Long? = null

    private fun now():Long? = runCatching { TrafficAccountingPolicy.total(
        TrafficStats.getUidRxBytes(Process.myUid()),TrafficStats.getUidTxBytes(Process.myUid())) }.getOrNull()

    /** Start of a session: count only what comes after. */
    fun start() { last = now() }

    fun tick() {
        val n=now(); val d=TrafficAccountingPolicy.delta(last,n); last=n
        if (d > 0) Store.update { it.copy(settings = add(it.settings, d, month())) }
    }

    fun month(t: Long = System.currentTimeMillis()): String = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(t))

    fun add(s: Settings, bytes: Long, month: String): Settings {
        val m = s.traffic + (month to TrafficAccountingPolicy.add(s.traffic[month] ?: 0L, bytes))
        return s.copy(traffic = m.toSortedMap().entries.toList().takeLast(3).associate { it.key to it.value })
    }

    /** Home screen line, or "" when nothing yet; [warn] = over 90 % of the user's monthly limit. */
    fun line(s: Settings): Pair<String, Boolean> {
        val b = s.traffic[month()] ?: return "" to false
        val lim = s.monthLimitGb * (1L shl 30)
        val warn = TrafficAccountingPolicy.nearLimit(b,lim)
        return ("Оценка приложения за месяц: " + Actions.bytes(b) + (if (lim > 0) " из ${s.monthLimitGb} ГБ" else "") + if (warn) " — лимит почти исчерпан" else "") to warn
    }
}
