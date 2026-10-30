package com.vlesscardvpn.core

/** User ceiling for simultaneous server routes, not a count of individual HTTP requests.
 * Device heat/memory/call limits always win. A core starts only the routes it can probe.
 */
object ProbeConcurrencyPolicy {
    const val DEFAULT = 5
    val choices = listOf(1, 3, 5, 10)
    fun normalize(value:Int):Int = value.takeIf {it in choices} ?: DEFAULT
    fun limit(requested:Int,selected:Int,plan:PowerPolicy.Plan,foreground:Boolean):Int =
        minOf(requested.coerceAtLeast(1),normalize(selected),
            (if(foreground) plan.foregroundRealParallel else plan.realParallel).coerceAtLeast(1))
    fun batch(requested:Int,slots:Int):Int = minOf(requested.coerceAtLeast(1),slots.coerceAtLeast(1))
}
