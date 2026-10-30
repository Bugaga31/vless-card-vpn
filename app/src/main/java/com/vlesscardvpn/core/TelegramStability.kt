package com.vlesscardvpn.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** Current route only. Cleared at tunnel/network boundaries; samples older than 3 minutes expire. */
object TelegramStability {
    private data class Timed(val at: Long, val sample: LinkStabilityPolicy.Sample)
    private var scope: String? = null
    private val samples = mutableListOf<Timed>()
    val revision = MutableStateFlow(0L)
    @Synchronized fun clear() { samples.clear(); scope=null; revision.value++ }
    @Synchronized fun record(ok: Boolean?, ms: Int, routeScope: String) {
        if(scope!=routeScope) {samples.clear();scope=routeScope}
        val now = SystemClock.elapsedRealtime()
        samples.removeAll { now < it.at || now - it.at > 180_000L }
        samples.add(Timed(now,LinkStabilityPolicy.Sample(ok,ms)))
        while(samples.size>5) samples.removeAt(0)
        revision.value++
    }
    @Synchronized fun summary(now: Long) = LinkStabilityPolicy.summarize(
        samples.filter { now >= it.at && now-it.at <= 180_000L }.map { it.sample }, AutoLatencyPolicy.MAX_MS)
}
