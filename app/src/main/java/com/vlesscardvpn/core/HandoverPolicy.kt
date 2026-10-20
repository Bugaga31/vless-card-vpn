package com.vlesscardvpn.core

/** Background upgrades only. Explicit Auto and confirmed failures do not wait for this gate. */
class HandoverPolicy {
    companion object { const val HOLD_MS = 60_000L; const val WINDOW_MS = 600_000L; const val COOLDOWN_MS = 120_000L }
    private var context = ""
    private var current = ""
    private var heldAt = 0L
    private var challenger = ""
    private var samples = 0
    private var sampleAt = 0L
    private var observedAt = 0L
    private val failures = linkedMapOf<String, Long>()
    @Synchronized fun failure(context: String, route: String, now: Long) {
        failures[context + "|" + route] = now
        while (failures.size > 64) failures.remove(failures.keys.first())
    }
    @Synchronized fun allow(context: String, current: String, candidate: String, now: Long,
                            measurementAt: Long, gain: Boolean, broken: Boolean): Boolean {
        if (this.context != context || this.current != current || now < heldAt) {
            this.context = context; this.current = current; heldAt = now
            challenger = ""; samples = 0; sampleAt = 0
        }
        if (candidate.isEmpty()) return false
        if (broken) return true
        if (!gain || candidate == current || measurementAt <= 0) {
            challenger = ""; samples = 0; sampleAt = 0; return false
        }
        val failedAt = failures[context + "|" + candidate]
        if (failedAt != null && now >= failedAt && now - failedAt < COOLDOWN_MS) return false
        if (candidate != challenger || now < observedAt || now - observedAt > WINDOW_MS) {
            challenger = candidate; samples = 0; sampleAt = 0
        }
        if (measurementAt > sampleAt) { samples++; sampleAt = measurementAt; observedAt = now }
        return now - heldAt >= HOLD_MS && samples >= 2
    }
}
