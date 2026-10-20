package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Pure policies used by automatic probing and ranking; no downloads or extra background work. */
object ServerChoice {
    /** Try different hosts before spending the whole test budget on many links to one machine.
     * Ranked order is preserved within each pass; remaining transport variants fill unused slots.
     */
    fun diverse(ranked: List<Server>, limit: Int): List<Server> {
        if (limit <= 0) return emptyList()
        val hosts = HashSet<String>()
        val first = ArrayList<Server>(); val rest = ArrayList<Server>()
        ranked.distinctBy { it.id }.forEach { s ->
            val host = s.address.lowercase(java.util.Locale.ROOT).removeSuffix(".").removeSurrounding("[", "]")
            if (hosts.add(host)) first += s else rest += s
        }
        return (first + rest).take(limit)
    }

    /** Discount a cloud opinion with little evidence instead of switching abruptly after two checks. */
    fun evidence(checks: Int): Double = checks.coerceAtLeast(0).toDouble() / (checks.coerceAtLeast(0).toDouble() + 6.0)

    /** Beta(2,2) prior: unknown is 50%; a few failures alone cannot blacklist a server. */
    fun successChance(percent: Int, checks: Int): Double {
        val n = checks.coerceAtLeast(0).toDouble()
        return (percent.coerceIn(0, 100) / 100.0 * n + 2.0) / (n + 4.0)
    }

    /** Small hysteresis for a working server already in use. A clearly faster spare still wins. */
    fun stayFactor(selected: Boolean): Double = if (selected) 0.92 else 1.0
    /** Gradual Bayesian warm start, exact-network local evidence wins as genuine successes accumulate. */
    fun blended(cloud: Double?, local: com.vlesscardvpn.model.RouteEvidence, now: Long): Double {
        val freshness = if (local.at <= 0 || now < local.at) 0.0 else kotlin.math.exp(-(now - local.at) / 86_400_000.0)
        val alpha = (.85 * local.successes.coerceIn(0, 5) / 5.0) * freshness
        val prior = cloud?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: .5
        return ((1 - alpha) * prior + alpha * local.ema - local.latency.coerceAtLeast(0.0) / 10000 - local.jitter.coerceAtLeast(0.0) / 20000).coerceIn(0.0, 1.0)
    }
    /** Latency-mode estimate; no claim of UDP loss measurement. Unknown evidence gets a conservative penalty. */
    fun latencyScore(local: com.vlesscardvpn.model.RouteEvidence, fallbackMs: Int): Double =
        (if (local.latency > 0) local.latency else fallbackMs.takeIf { it > 0 }?.toDouble() ?: 3000.0) +
            2 * local.jitter.coerceAtLeast(0.0) + (1 - local.ema.coerceIn(0.0,1.0)) * 1000 +
            (if (local.checks < 2) 200 else 0)
    /** A throughput sample applies only to its measured network, mask and recent window. */
    fun measuredSpeed(s: com.vlesscardvpn.model.ServerState, net: String, now: Long): Int =
        s.kbps.takeIf { it > 0 && s.speedNetwork == net && s.speedMask == s.maskFor(net) &&
            s.speedCheckedAt > 0 && now >= s.speedCheckedAt && now-s.speedCheckedAt <= 30 * 60000L } ?: 0
    fun qualityScore(s: com.vlesscardvpn.model.ServerState, net: String, now: Long): Int = if (!s.works) Int.MAX_VALUE
        else s.realMs.coerceAtMost(60000) + com.vlesscardvpn.model.ServerState.speedPenalty(measuredSpeed(s,net,now)) + if (s.ytOk == false) 700 else 0
    /** Legacy/foreign speed is a candidate hint only, never a current-network live rating. */
    fun candidateScore(s: com.vlesscardvpn.model.ServerState, net: String, now: Long): Int =
        if (s.works && measuredSpeed(s,net,now)==0 && s.kbps>0)
            s.realMs.coerceIn(0,60000) + com.vlesscardvpn.model.ServerState.speedPenalty(s.kbps) + 1500
        else qualityScore(s,net,now)
    /** Service proof first, then current-network throughput; ping only breaks equal-speed ties. */
    fun compareDownload(a: com.vlesscardvpn.model.ServerState, b: com.vlesscardvpn.model.ServerState,
                        net: String, now: Long): Int {
        if (a.works != b.works) return if (a.works) -1 else 1
        val aService = AutoEligibility.serviceRank(a,net,now)
        val bService = AutoEligibility.serviceRank(b,net,now)
        val service = aService.compareTo(bService)
        if (service != 0) return service
        val speed = measuredSpeed(b,net,now).compareTo(measuredSpeed(a,net,now))
        if (speed != 0) return speed
        return qualityScore(a,net,now).compareTo(qualityScore(b,net,now))
    }
    fun downloadOrder(state: (Server) -> com.vlesscardvpn.model.ServerState, net: String, now: Long): Comparator<Server> =
        Comparator { a,b -> compareDownload(state(a),state(b),net,now) }
    /** Background handovers need a material gain; explicit Auto still chooses the highest measured rate. */
    fun downloadUpgrade(current: com.vlesscardvpn.model.ServerState?, candidate: com.vlesscardvpn.model.ServerState,
                        net: String, now: Long): Boolean {
        if (!candidate.works) return false
        val candidateRank = AutoEligibility.serviceRank(candidate,net,now)
        val currentRank = current?.takeIf { it.works }?.let { AutoEligibility.serviceRank(it,net,now) }
        if (currentRank != null && candidateRank != currentRank) return candidateRank < currentRank
        if (candidateRank == 2) return false
        val speed = measuredSpeed(candidate,net,now)
        if (speed <= 0) return false
        val old = current?.takeIf { it.works }?.let { measuredSpeed(it,net,now) } ?: 0
        return old <= 0 || speed.toLong() >= old.toLong() * 120 / 100 && speed.toLong() - old >= 500
    }
    /** Keep a historical hint, but never reuse an unconfirmed speed as a live rating.
     * A newer concurrent sample or another network is never invalidated.
     */
    fun invalidateSpeed(s: com.vlesscardvpn.model.ServerState, net: String, attemptedAt: Long): com.vlesscardvpn.model.ServerState =
        if (attemptedAt > 0 && s.speedNetwork == net && s.speedCheckedAt == attemptedAt) s.copy(speedCheckedAt = 0) else s
    fun acceptsSpeedSample(kbps: Int, parallel: Int) = kbps > 0 && parallel == 1
    fun automaticPrimary(auto: Boolean, leastPing: Boolean, gaming: Boolean) = gaming || auto && leastPing
    /** Honest upper-confidence exploration bonus; use on background candidates, not forced live switches. */
    fun exploration(checks: Int, total: Int): Double = kotlin.math.sqrt(2 * kotlin.math.ln((total.coerceAtLeast(0) + 2).toDouble()) / (checks.coerceAtLeast(0) + 1))

}
