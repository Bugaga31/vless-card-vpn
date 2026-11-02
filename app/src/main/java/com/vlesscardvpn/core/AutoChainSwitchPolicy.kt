package com.vlesscardvpn.core

/** Hysteresis: service confirmation before speed; no model-generated PASS. */
object AutoChainSwitchPolicy {
    fun purpose(id: String) = when(id) {
        "download" -> AutoChainSelection.Purpose.DOWNLOAD
        "interactive" -> AutoChainSelection.Purpose.INTERACTIVE
        else -> AutoChainSelection.Purpose.BALANCED
    }
    /** Preserve fresh negative evidence as well as PASS. Missing/late evidence stays UNKNOWN. */
    fun currentEvidence(sample: AutoChainSelection.Sample?, chainId: String?, epoch: Long,
                        now: Long): AutoChainSelection.Sample? = sample?.takeIf {
        chainId != null && it.chainId == chainId && it.generation == epoch &&
            it.sampledAtMs >= 0 && now >= it.sampledAtMs &&
            now - it.sampledAtMs <= AutoChainSelection.FRESH_MS
    }
    fun shouldSwitch(current: AutoChainSelection.Sample?, winner: AutoChainSelection.Sample,
                     purpose: AutoChainSelection.Purpose, currentExpected: Boolean = false): Boolean {
        if (winner.data != AutoChainSelection.Verdict.PASS || !winner.verifiedBody ||
            winner.telegram == AutoChainSelection.Verdict.FAIL || winner.youtube == AutoChainSelection.Verdict.FAIL) return false
        // A missing trial is UNKNOWN, not proof that a working live route failed.
        if (current == null) return !currentExpected
        if (current.chainId == winner.chainId) return false
        // Recovery is not speed optimization: a definite failed trial must not be
        // protected by the 20% speed hysteresis. UNKNOWN alone is not a failure.
        if (current.data == AutoChainSelection.Verdict.FAIL ||
            current.telegram == AutoChainSelection.Verdict.FAIL ||
            current.youtube == AutoChainSelection.Verdict.FAIL) return true
        fun confirmed(s: AutoChainSelection.Sample) = s.telegram == AutoChainSelection.Verdict.PASS && s.youtube == AutoChainSelection.Verdict.PASS
        if (confirmed(current) != confirmed(winner)) return confirmed(winner)
        if (purpose == AutoChainSelection.Purpose.INTERACTIVE)
            return winner.latencyMs * 100 <= current.latencyMs * 80 && current.latencyMs - winner.latencyMs >= 50
        val old = AutoChainSelection.transferKbps(current); val fresh = AutoChainSelection.transferKbps(winner)
        if (purpose == AutoChainSelection.Purpose.BALANCED && winner.latencyMs > current.latencyMs + 100) return false
        return fresh >= old * 120 / 100 && fresh - old >= 256
    }
}
