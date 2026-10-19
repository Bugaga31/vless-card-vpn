package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks

/** Local observations are hints, never a reason to relax transport/certificate/user constraints. */
object MaskSearchPolicy {
    fun allowed(m: Mask, s: Server, cfg: Settings, byeDpi: Boolean, tpws: Boolean): Boolean =
        Masks.compatible(m, s) && (!m.viaByeDpi || byeDpi) &&
        (tpws || !m.dpi.startsWith("TPWS#")) &&
        (m.id == Masks.DEFAULT.id || m.custom ||
            (cfg.maskFamilies.isEmpty() || Masks.family(m) in cfg.maskFamilies) &&
            (cfg.maskFps.isEmpty() || m.fingerprint in cfg.maskFps || m.hasNoise))

    /** One old success must not put a now repeatedly failing recipe ahead of untested alternatives. */
    fun bucket(stat: MaskStat?): Int = when {
        stat == null -> 1
        stat.fail >= 4 && stat.score < .4 -> 2
        stat.ok >= 2 && stat.score >= .6 -> 0
        else -> 1
    }

    /** Real media throughput matters when measured; a fast 204 response alone cannot win video selection. */
    fun cost(m: Mask, p: Probe): Int {
        if (!p.works) return Int.MAX_VALUE
        val speed = if (p.ytOk == true && p.ytKbps > 0) p.ytKbps else p.kbps
        val penalty = ServerState.speedPenalty(speed)
        return (p.realMs.toLong() + Masks.cost(m) * 150L + penalty +
            if (p.ytOk == false) 1500L else 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
