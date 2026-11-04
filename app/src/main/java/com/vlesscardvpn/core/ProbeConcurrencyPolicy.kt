package com.vlesscardvpn.core

/** User ceiling for simultaneous server routes, not a count of individual HTTP requests.
 * The explicit user ceiling is not reduced by app power/call policies. Android still owns system thermal safety.
 */
object ProbeConcurrencyPolicy {
    const val DEFAULT = 5
    val choices = listOf(1, 3, 5, 10)
    fun normalize(value:Int):Int = value.takeIf {it in choices} ?: DEFAULT
    @Suppress("UNUSED_PARAMETER")
    fun limit(requested:Int,selected:Int,plan:PowerPolicy.Plan,foreground:Boolean):Int =
        minOf(requested.coerceAtLeast(1),normalize(selected))
    fun batch(requested:Int,slots:Int):Int = minOf(requested.coerceAtLeast(1),slots.coerceAtLeast(1))
}
