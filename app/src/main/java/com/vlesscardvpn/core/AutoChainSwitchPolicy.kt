package com.vlesscardvpn.core

/** Hysteresis: service confirmation before speed; no model-generated PASS. */
object AutoChainSwitchPolicy {
    fun purpose(id: String) = when(id) {
        "download" -> AutoChainSelection.Purpose.DOWNLOAD
        "interactive" -> AutoChainSelection.Purpose.INTERACTIVE
        else -> AutoChainSelection.Purpose.BALANCED
    }
    fun shouldSwitch(current: AutoChainSelection.Sample?, winner: AutoChainSelection.Sample,
                     purpose: AutoChainSelection.Purpose): Boolean {
        if (winner.data != AutoChainSelection.Verdict.PASS || !winner.verifiedBody ||
            winner.telegram == AutoChainSelection.Verdict.FAIL || winner.youtube == AutoChainSelection.Verdict.FAIL) return false
        if (current == null) return true
        if (current.chainId == winner.chainId) return false
        fun confirmed(s: AutoChainSelection.Sample) = s.telegram == AutoChainSelection.Verdict.PASS && s.youtube == AutoChainSelection.Verdict.PASS
        if (confirmed(current) != confirmed(winner)) return confirmed(winner)
        if (purpose == AutoChainSelection.Purpose.INTERACTIVE)
            return winner.latencyMs * 100 <= current.latencyMs * 80 && current.latencyMs - winner.latencyMs >= 50
        fun rate(s: AutoChainSelection.Sample) = s.bodyBytes * 8 / s.transferMs.coerceAtLeast(1)
        val old = rate(current); val fresh = rate(winner)
        if (purpose == AutoChainSelection.Purpose.BALANCED && winner.latencyMs > current.latencyMs + 100) return false
        return fresh >= old * 120 / 100 && fresh - old >= 256
    }
}
