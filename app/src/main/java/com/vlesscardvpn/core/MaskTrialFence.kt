package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings

/** Admission/training fence for an isolated server+mask trial, not a heuristic gate.
 * UI/theme/power changes do not cancel explicit VPN work. Transport/certificate edits do. */
object MaskTrialFence {
    /** Exact immutable identity, RAM only. Do not log: custom profile strings may be private. */
    class Context internal constructor(private val values: List<Any>) {
        override fun equals(other: Any?) = other is Context && values == other.values
        override fun hashCode() = values.hashCode()
        override fun toString() = "MaskTrialContext(redacted)"
    }
    fun context(s: Settings): Context = Context(listOf(
        s.mode, s.testUrl, s.autoChecks.toSet(),
        AutoLatencyRange.safe(s.autoLatencyMinMs, s.autoLatencyMaxMs).max,
        s.maskFamilies.toList(), s.maskFps.toList(), s.maskEvolution,
        s.myMasks.toList(), s.autoMasks.toList(), s.dpiStrategy,
        s.byeDpiArgs, s.byeDpiSni, s.dpiEvoPool.toList(), s.dpiRemembered.toMap()
    ))
    fun settingsUnchanged(initial: Settings, current: Settings): Boolean =
        current.mode == Mode.AUTO && context(initial) == context(current)

    fun serverUnchanged(tested:Server,current:List<Server>):Boolean = tested in current
}
