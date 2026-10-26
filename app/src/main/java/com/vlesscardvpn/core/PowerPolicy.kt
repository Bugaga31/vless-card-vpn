package com.vlesscardvpn.core

/** Pure policy: optional work only. It never disconnects the VPN or blocks urgent repairs. */
object PowerPolicy {
    data class Input(val now: Long, val battery: Int = -1, val charging: Boolean = false,
        val saver: Boolean = false, val screen: Boolean = true, val lowMemory: Boolean = false,
        val gaming: Boolean = false, val recentLag: Boolean = false, val dataSaver: Boolean = false,
        val activeTraffic: Boolean = false)
    data class Options(val quietScreen: Boolean = false, val protectTraffic: Boolean = true)
    data class Plan(val tcpParallel: Int, val realParallel: Int, val chargeWork: Boolean,
        val urgentRepairs: Boolean, val bgWork: Boolean, val quiet: Boolean)

    @Volatile private var hot = false
    @Volatile private var since = -1L
    @Volatile private var pauseUntil = 0L
    @Volatile private var connectedAt = 0L
    private val WARMUP_MS = 120_000L

    @Synchronized fun reset() { hot = false; since = -1; pauseUntil = 0; connectedAt = 0 }
    @Synchronized fun connect(now: Long) { connectedAt = now; pauseUntil = maxOf(pauseUntil, now + WARMUP_MS) }
    @Synchronized fun pause(now: Long, durationMs: Long) { pauseUntil = maxOf(pauseUntil, now + durationMs.coerceAtLeast(0)) }
    @Synchronized fun manualRun(now: Long) { pause(now, 30 * 60_000L) }

    @Synchronized fun plan(i: Input, o: Options = Options()): Plan {
        val charging = i.charging
        val low = i.battery in 0..20 && !charging
        if (i.gaming || i.recentLag || i.lowMemory || low || i.saver) {
            if (!hot) { hot = true; since = i.now }
        } else if (hot) {
            if (since < 0) since = i.now
            if (i.now - since >= WARMUP_MS) { hot = false; since = -1 }
        }
        val chargeWork = i.charging && i.battery >= 0 && i.battery < 100
        val quiet = hot || i.lowMemory || i.gaming || i.recentLag || i.saver || low
        val due = i.now >= pauseUntil
        val bg = due && !quiet && !i.dataSaver && (i.screen || !o.quietScreen || chargeWork)
        val repairs = !o.protectTraffic || !i.activeTraffic
        return Plan(
            tcpParallel = if (quiet) 2 else if (chargeWork) 16 else 8,
            realParallel = if (quiet) 1 else if (chargeWork) 6 else 3,
            chargeWork = chargeWork && !hot && !i.lowMemory && !i.gaming && !i.recentLag && !i.saver && !low && !(i.now < pauseUntil) && !(!i.screen && o.quietScreen && !chargeWork) && !i.dataSaver,
            urgentRepairs = repairs,
            bgWork = bg,
            quiet = quiet
        )
    }
    fun cap(requested: Int, limit: Int) = requested.coerceIn(1, limit.coerceAtLeast(1))
}
