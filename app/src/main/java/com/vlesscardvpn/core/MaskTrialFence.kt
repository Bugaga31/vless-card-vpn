package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings

/** Admission/training fence for an isolated server+mask trial, not a heuristic gate.
 * UI/theme/power changes do not cancel explicit VPN work. Transport/certificate edits do. */
object MaskTrialFence {
    fun settingsUnchanged(initial:Settings,current:Settings):Boolean =
        current.mode==Mode.AUTO && current.mode==initial.mode &&
        current.testUrl==initial.testUrl && current.autoChecks==initial.autoChecks &&
        AutoLatencyRange.safe(current.autoLatencyMinMs,current.autoLatencyMaxMs).max==
            AutoLatencyRange.safe(initial.autoLatencyMinMs,initial.autoLatencyMaxMs).max &&
        current.maskFamilies==initial.maskFamilies && current.maskFps==initial.maskFps &&
        current.maskEvolution==initial.maskEvolution && current.myMasks==initial.myMasks &&
        current.autoMasks==initial.autoMasks && current.dpiStrategy==initial.dpiStrategy &&
        current.byeDpiArgs==initial.byeDpiArgs && current.byeDpiSni==initial.byeDpiSni &&
        current.dpiEvoPool==initial.dpiEvoPool

    fun serverUnchanged(tested:Server,current:List<Server>):Boolean = tested in current
}
