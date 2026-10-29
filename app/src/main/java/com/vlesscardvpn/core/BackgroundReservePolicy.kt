package com.vlesscardvpn.core

/** Light Auto reserve work is distinct from optional throughput downloads. */
object BackgroundReservePolicy {
    const val RETRY_MS = 5000L
    const val AUTO_PASS_MS = 30000L
    fun mayProbe(auto: Boolean, plan: PowerPolicy.Plan) = plan.allowLight || auto && plan.allowReserve
    fun candidates(plan: PowerPolicy.Plan) = if (plan.allowLight) ConnectPolicy.BACKGROUND else 2
    fun mayApply(protectTraffic: Boolean, busy: Boolean, voiceCall: Boolean) = !voiceCall && (!protectTraffic || !busy)
    fun next(auto: Boolean, calm: Int) = if (auto) AUTO_PASS_MS else longArrayOf(60000L,120000L,180000L,300000L)[calm.coerceIn(0,3)]
}
