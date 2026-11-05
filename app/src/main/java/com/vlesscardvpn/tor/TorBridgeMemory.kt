package com.vlesscardvpn.tor

import java.util.Locale

/**
 * What Tor itself reported about each bridge (`GETINFO entry-guards`), kept per transport across restarts:
 * a rotation keeps the bridges that work, prefers ones that worked before and skips ones that failed recently.
 * Pure and clock-injected; only lines already validated by [TorBridgeCatalog.normalize] are remembered.
 */
object TorBridgeMemory {
    const val GOOD_MAX = 8
    const val BAD_MAX = 200
    /**
     * A bridge Tor marked down is skipped this long, then gets another chance (bridges come back; a long outage of
     * the route itself can also mark a good one down). Tor reports «down» only for a bridge it had confirmed before:
     * untried and never-working ones stay «never-connected» and are judged by the rotation alone.
     */
    const val BAD_TTL_MS = 6 * 60 * 60_000L

    private val fingerprintShape = Regex("[A-Fa-f0-9]{40}")
    private val guardLine = Regex("^\\$([A-Fa-f0-9]{40})(?:[~=]\\S{0,64})?\\s+(up|down|never-connected|unusable)(?:\\s|$)")

    /** Fingerprint (upper-case hex) to Tor's status: «up», «down», «never-connected» or «unusable». */
    fun parseGuards(reply: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        reply.lineSequence().map { it.trim().substringAfter("entry-guards=") }.forEach { line ->
            val m = guardLine.find(line) ?: return@forEach
            out.putIfAbsent(m.groupValues[1].uppercase(Locale.ROOT), m.groupValues[2])
        }
        return out
    }

    fun fingerprint(line: String): String? = line.trim().removePrefix("Bridge ").split(Regex(" +")).getOrNull(2)
        ?.takeIf(fingerprintShape::matches)?.uppercase(Locale.ROOT)

    /** Bridges of [lines] that Tor confirmed and still considers reachable. */
    fun working(lines: List<String>, guards: Map<String, String>): List<String> = lines.filter { guards[fingerprint(it)] == "up" }
    /** Bridges of [lines] whose last connection attempt failed. */
    fun failing(lines: List<String>, guards: Map<String, String>): List<String> = lines.filter { guards[fingerprint(it)] == "down" }

    /** [good]: lines that worked, newest first. [bad]: fingerprint → when Tor last reported it down. */
    data class State(val good: List<String> = emptyList(), val bad: Map<String, Long> = emptyMap())

    fun note(state: State, lines: List<String>, guards: Map<String, String>, now: Long): State {
        val up = working(lines, guards)
        val upIds = up.mapNotNull(::fingerprint).toSet()
        val downIds = failing(lines, guards).mapNotNull(::fingerprint).toSet()
        val good = (up + state.good).filter { fingerprint(it) != null && fingerprint(it) !in downIds }
            .distinctBy { fingerprint(it) }.take(GOOD_MAX)
        val bad = (state.bad - upIds - downIds).filterValues { fresh(it, now) } + downIds.associateWith { now }
        return State(good, bad.entries.sortedByDescending { it.value }.take(BAD_MAX).associate { it.key to it.value })
    }

    /** Fingerprints to skip now. */
    fun bad(state: State, now: Long): Set<String> = state.bad.filterValues { fresh(it, now) }.keys

    private fun fresh(since: Long, now: Long) = now >= since && now - since < BAD_TTL_MS

    fun encodeGood(state: State): String = state.good.joinToString("\n")
    fun encodeBad(state: State): String = state.bad.entries.joinToString("\n") { "${it.key} ${it.value}" }
    fun decode(good: String?, bad: String?, transport: String): State = State(
        good.orEmpty().lineSequence().mapNotNull { TorBridgeCatalog.normalize(it) }
            .filter { it.startsWith("Bridge $transport ") }.distinctBy { fingerprint(it) }.take(GOOD_MAX).toList(),
        bad.orEmpty().lineSequence().mapNotNull { row ->
            val id = row.substringBefore(' ').takeIf(fingerprintShape::matches)?.uppercase(Locale.ROOT)
            val since = row.substringAfter(' ', "").toLongOrNull()
            if (id != null && since != null && since > 0) id to since else null
        }.take(BAD_MAX).toMap())
}
