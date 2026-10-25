package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings

/** Two in-memory, confirmed alternatives. Never persists endpoints or promotes old
 * network evidence. Taking a reserve consumes it; the live tunnel must re-check it.
 */
class AutoChainReserves {
    private val entries = LinkedHashMap<String, AutoChainRunner.Measured>()
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun discard(chainId: String) { entries.remove(chainId) }
    @Synchronized fun record(value: AutoChainRunner.Measured, settings: Settings, network: String,
                             epoch: Long, now: Long, activeId: String?) {
        entries.entries.removeAll { !AutoChainRunner.fresh(it.value, settings, network, epoch, now) || it.key == activeId }
        // A newer UNKNOWN/FAIL must not leave an earlier positive sample usable.
        entries.remove(value.choice.chain.id)
        if (value.choice.chain.id == activeId || !confirmed(value) ||
            !AutoChainRunner.fresh(value, settings, network, epoch, now)) return
        entries[value.choice.chain.id] = value
        while (entries.size > 2) entries.remove(entries.keys.first())
    }
    @Synchronized fun take(settings: Settings, network: String, epoch: Long, now: Long,
                           activeId: String? = null): AutoChainRunner.Measured? {
        entries.entries.removeAll { !confirmed(it.value) || !AutoChainRunner.fresh(it.value, settings, network, epoch, now) || it.key == activeId }
        val value = entries.values.maxByOrNull { it.sample.sampledAtMs } ?: return null
        entries.remove(value.choice.chain.id)
        return value
    }
    /** Hints only: after handoff each endpoint needs a new-network probe. No samples leave this cache. */
    @Synchronized fun candidateServers() = entries.values.mapNotNull { it.choice.server }.distinctBy { it.id }
    private fun confirmed(m: AutoChainRunner.Measured) =
        m.sample.telegram == AutoChainSelection.Verdict.PASS && m.sample.youtube == AutoChainSelection.Verdict.PASS
}
