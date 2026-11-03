package com.vlesscardvpn.core

/** User ceiling for simultaneous server routes, not a count of individual HTTP requests.
 * The explicit user ceiling is not reduced by app power/call policies. Android still owns system thermal safety.
 * Any whole number 1..[MAX] may be entered; [choices] are only quick presets.
 * [deviceCeiling] is a resource guard for 32-bit processes (thread stacks share a 4 GB address space),
 * not a Watson/power decision. Achieved concurrency is reported separately by [ProbeInFlight].
 */
object ProbeConcurrencyPolicy {
    const val DEFAULT = 100
    const val MAX = 1000
    /** The old default; settings saved before [SCHEMA] with this value are migrated to [DEFAULT] once. */
    const val LEGACY_DEFAULT = 5
    const val SCHEMA = 2
    const val CEILING_32_BIT = 150
    val choices = listOf(1, 3, 5, 10, 100, 1000)
    fun normalize(value:Int):Int = if(value<=0) DEFAULT else value.coerceAtMost(MAX)
    /** Free text from the settings field: digits only, otherwise null (keep the old value). */
    fun parse(text:String):Int? = text.trim().takeIf {it.isNotEmpty() && it.length<=7 && it.all(Char::isDigit)}
        ?.toIntOrNull()?.takeIf {it>=1}?.coerceAtMost(MAX)
    fun migrate(stored:Int?,schema:Int):Int = when {
        stored==null -> DEFAULT
        schema<SCHEMA && stored==LEGACY_DEFAULT -> DEFAULT
        else -> normalize(stored)
    }
    @Volatile var deviceCeiling:Int = MAX
        private set
    fun initDevice(is64Bit:Boolean) {deviceCeiling=if(is64Bit) MAX else CEILING_32_BIT}
    @Suppress("UNUSED_PARAMETER")
    fun limit(requested:Int,selected:Int,plan:PowerPolicy.Plan,foreground:Boolean):Int =
        minOf(requested.coerceAtLeast(1),normalize(selected),deviceCeiling)
    fun batch(requested:Int,slots:Int):Int = minOf(requested.coerceAtLeast(1),slots.coerceAtLeast(1))
}

/** Actual simultaneously running route probes (not the configured ceiling). */
object ProbeInFlight {
    private val now = java.util.concurrent.atomic.AtomicInteger()
    private val top = java.util.concurrent.atomic.AtomicInteger()
    val current:Int get() = now.get()
    val peak:Int get() = top.get()
    suspend fun <T> trackSuspend(block:suspend ()->T):T {
        val n=now.incrementAndGet()
        top.accumulateAndGet(n) {a,b->maxOf(a,b)}
        try {return block()} finally {now.decrementAndGet()}
    }
    fun resetPeak() {top.set(now.get())}
}
