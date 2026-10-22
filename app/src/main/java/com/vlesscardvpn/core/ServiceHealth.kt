package com.vlesscardvpn.core

/** Actual chain observations in RAM only. No endpoints, keys or device identifiers. */
object ServiceHealth {
    enum class Verdict { PASS, FAIL, UNKNOWN, STALE, CHECKING }
    data class Snapshot(val checkedAt: Long = 0, val youtube: Boolean? = null, val telegram: Boolean? = null,
                        val checking: Boolean = false, val budgetMs: Long = 0, val youtubeReason: String = "", val telegramReason: String = "", val webOnly: Boolean = false)
    enum class EventKind { SEARCH, CHECK, UNKNOWN, FAILURE, CONNECTED, NETWORK_CHANGE }
    data class Event(val at: Long, val kind: EventKind)
    val history = kotlinx.coroutines.flow.MutableStateFlow<List<Event>>(emptyList())
    @Synchronized fun event(kind: EventKind, now: Long = System.currentTimeMillis()) {
        if (history.value.firstOrNull()?.let { it.kind == kind && now - it.at in 0..1000 } == true) return
        history.value = (listOf(Event(now, kind)) + history.value).take(24)
    }
    val state = kotlinx.coroutines.flow.MutableStateFlow(Snapshot())
    fun verdict(value: Boolean?, snapshot: Snapshot, now: Long): Verdict = when {
        snapshot.checking -> Verdict.CHECKING
        snapshot.checkedAt <= 0 -> Verdict.UNKNOWN
        now < snapshot.checkedAt || now - snapshot.checkedAt > AutoEligibility.SERVICE_TTL_MS -> Verdict.STALE
        value == true -> Verdict.PASS
        value == false -> Verdict.FAIL
        else -> Verdict.UNKNOWN
    }
    fun begin(budgetMs: Long) { event(EventKind.CHECK); state.value = Snapshot(checking = true, budgetMs = budgetMs) }
    fun finish(probe: Probe, now: Long, budgetMs: Long) {
        state.value = Snapshot(now, if (probe.web != null) probe.web.youtube else probe.ytOk, if (probe.web != null) probe.web.telegram else probe.tgOk, budgetMs = budgetMs, youtubeReason = probe.ytReason, telegramReason = probe.tgReason, webOnly = probe.web != null)
        val youtube = if (probe.web != null) probe.web.youtube else probe.ytOk
        val telegram = if (probe.web != null) probe.web.telegram else probe.tgOk
        if (youtube == false || telegram == false) event(EventKind.FAILURE, now)
        else if (youtube == null || telegram == null) event(EventKind.UNKNOWN, now)
    }
    fun reset() { state.value = Snapshot() }
}
