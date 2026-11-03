package com.vlesscardvpn.core

/** Reliability work, separate from optional speed/phone optimization. Never applies a route. */
object StandbyPreparationPolicy {
    const val TARGET = 3
    const val POLL_MS = 5_000L
    const val ATTEMPT_INTERVAL_MS = 30_000L
    const val PASS_TIMEOUT_MS = 45_000L
    fun mayPrepare(auto: Boolean, plan: PowerPolicy.Plan) = auto && !plan.memory.hot && !plan.voiceCallActive
    /** Continue only when a new fully verified reserve was actually recorded. */
    fun continueAfter(before: Int, after: Int) = before >= 0 && after > before && after < TARGET
    fun due(now: Long, lastAttempt: Long?, warm: Int, busy: Boolean): Boolean =
        warm < TARGET && !busy && (lastAttempt == null || now < lastAttempt || now - lastAttempt >= ATTEMPT_INTERVAL_MS)
}
