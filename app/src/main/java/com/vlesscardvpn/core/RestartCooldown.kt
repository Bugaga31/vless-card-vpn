package com.vlesscardvpn.core

/** Monotonic elapsed-time limits; -1 means no previous restart/network transition. */
object RestartCooldown {
    const val HARD_MS = 5 * 60_000L
    const val ENGINE_MS = 60_000L
    const val NETWORK_MS = 90_000L
    fun since(now: Long, then: Long): Long? = if (then < 0 || now < then) null else now - then
    fun hardDue(now: Long, last: Long): Boolean = since(now,last)?.let { it >= HARD_MS } ?: true
    fun engineDue(now: Long, last: Long): Boolean = since(now,last)?.let { it >= ENGINE_MS } ?: true
    fun recentNetwork(now: Long, changed: Long): Boolean = since(now,changed)?.let { it < NETWORK_MS } ?: false
}
