package com.vlesscardvpn.core

/** A rejected >1000ms cold service probe must not occupy a fast-discovery slot for 1.8s.
 * Slack is for scheduling/cleanup only; acceptance still uses the measured <=1000ms gate.
 * Downloads, calls, ordinary diagnostics and background comparisons keep their full budget.
 */
object StrictProbeBudget {
    const val SCHEDULING_SLACK_MS = 100L
    fun budget(requested: Long, foreground: Boolean, requireTelegram: Boolean,
               big: Boolean, youtube: Boolean, calls: Boolean, attempts: Int): Long =
        if (foreground && requireTelegram && !big && !youtube && !calls && attempts == 1)
            if (requested > 0) minOf(requested, AutoLatencyPolicy.MAX_MS + SCHEDULING_SLACK_MS)
            else AutoLatencyPolicy.MAX_MS + SCHEDULING_SLACK_MS
        else requested
}
