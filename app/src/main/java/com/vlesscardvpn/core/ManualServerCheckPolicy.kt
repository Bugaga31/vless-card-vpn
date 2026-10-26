package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

object ManualServerCheckPolicy {
    data class Limits(val tcpParallel: Int, val realParallel: Int, val batch: Int)

    fun limits(power: PowerPolicy.Plan): Limits {
        val restricted = !power.allowLight || power.tcpParallel <= 16 || power.realParallel <= 2
        val tcp = PowerPolicy.cap(if (restricted) 4 else 12, power.tcpParallel)
        val real = PowerPolicy.cap(if (restricted) 2 else 4, power.realParallel)
        return Limits(tcp, real, if (restricted) 4 else 16)
    }

    fun confirmedTcpFailure(server: Server, state: ServerState, now: Long, freshnessMs: Long = 86_400_000L): Boolean {
        val checkedAt = state.checkedAt
        return server.isTcpBased && state.tcpMs == 0 && checkedAt > 0 && now >= checkedAt && now - checkedAt <= freshnessMs
    }
}
