package com.vlesscardvpn.core

import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/** Bounded policy used by AutoChainRunner and TunnelService. Order is DEVICE -> INTERNET.
 * Layout support must be supplied by an actually implemented adapter, not inferred
 * from the presence of a Tor SOCKS port. No endpoint/credential rewriting here.
 */
object AutoChainSelection {
    const val MAX_CANDIDATES = 8
    const val MAX_PLANNING_INPUT = 32
    const val TOTAL_MS = 15_000L
    const val PROBE_MS = 3_000L
    const val MIN_START_MS = 100L
    const val FRESH_MS = 60_000L
    const val MIN_BODY_BYTES = 16_384L
    enum class Layout(val server: Boolean, val tor: Boolean, val dpi: Boolean) {
        SERVER(true, false, false), DPI_SERVER(true, false, true),
        TOR(false, true, false), DPI_TOR(false, true, true),
        TOR_SERVER(true, true, false), DPI_TOR_SERVER(true, true, true),
        SERVER_TOR(true, true, false), DPI_SERVER_TOR(true, true, true)
    }
    enum class Verdict { PASS, FAIL, UNKNOWN }
    enum class Purpose { BALANCED, DOWNLOAD, INTERACTIVE }
    enum class State { CONFIRMED, FALLBACK, NO_ROUTE, NETWORK_CHANGED }

    data class Chain(val layout: Layout, val serverKey: String = "", val maskKey: String = "native",
                     val torProfileKey: String = "", val dpiKey: String = "", val tcpServer: Boolean = true,
                     val serverExitAllowed: Boolean = true) {
        init {
            listOf(serverKey, maskKey, torProfileKey, dpiKey).forEach { key ->
                require(key.length <= 128 && key.all { it.isLetterOrDigit() || it in "._-" }) { "Invalid immutable configuration key" }
            }
            require(serverKey.isNotEmpty() == layout.server)
            require(torProfileKey.isNotEmpty() == layout.tor)
            require(dpiKey.isNotEmpty() == layout.dpi)
        }
        // Include exact layout, server, mask, Tor profile and DPI strategy. Never reuse
        // standalone server evidence for a chain, or one bridge profile for another.
        val id: String get() = MessageDigest.getInstance("SHA-256").digest(
            listOf(layout.name, serverKey, maskKey, torProfileKey, dpiKey, tcpServer, serverExitAllowed)
                .joinToString("|").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    data class Capabilities(val implemented: Set<Layout>, val torOptIn: Boolean = false,
                            val torReady: Boolean = false, val requiresUdp: Boolean = false)
    data class Sample(val chainId: String, val generation: Long, val sampledAtMs: Long,
                      val data: Verdict, val telegram: Verdict, val youtube: Verdict,
                      val verifiedBody: Boolean, val bodyBytes: Long, val transferMs: Long, val latencyMs: Long, val exitCountry: String? = null,
                      val youtubeKbps: Int = 0)
    interface Session : AutoCloseable {
        /** Must be bounded and non-blocking; the cooperative timeout cannot preempt
         * arbitrary blocking native code. Resource teardown belongs to the adapter. */
        override fun close()
        /** Must check actual expected HTTPS body through this exact chain, not TCP connect.
         * Service values describe only the actual probe used (web != MTProto/media).
         * Adapter must invalidate evidence after Tor NEWNYM/circuit/profile change.
         */
        suspend fun measure(remainingMs: Long): Sample
    }
    data class Outcome(val state: State, val selected: Chain?, val sample: Sample?,
                       val attempted: Int, val fallbackCount: Int, val diagnostics: List<String>)

    fun incompatible(chain: Chain, capabilities: Capabilities): String? = when {
        chain.layout !in capabilities.implemented -> "adapter-not-implemented"
        !chain.serverExitAllowed -> "disallowed-server-exit"
        chain.layout.tor && !capabilities.torOptIn -> "tor-not-opted-in"
        chain.layout.tor && !capabilities.torReady -> "tor-not-ready"
        chain.layout.tor && capabilities.requiresUdp -> "tor-has-no-udp"
        (chain.layout.tor || chain.layout.dpi) && chain.layout.server && !chain.tcpServer -> "requires-tcp-server"
        else -> null
    }
    fun plan(chains: List<Chain>, capabilities: Capabilities): List<Chain> = chains.asSequence().take(MAX_PLANNING_INPUT)
        .distinctBy { it.id }.filter { incompatible(it, capabilities) == null }.take(MAX_CANDIDATES).toList()

    fun eligible(s: Sample, chain: Chain, generation: Long, nowMs: Long): Boolean =
        s.chainId == chain.id && s.generation == generation && s.sampledAtMs >= 0 &&
        nowMs >= s.sampledAtMs && nowMs - s.sampledAtMs <= FRESH_MS &&
        s.data == Verdict.PASS && s.telegram != Verdict.FAIL && s.youtube != Verdict.FAIL &&
        ( !chain.layout.tor || (s.exitCountry?.matches(Regex("[A-Z]{2}")) == true && s.exitCountry != "RU")) &&
        s.verifiedBody && s.bodyBytes in MIN_BODY_BYTES..4_194_304L &&
        s.transferMs in 1..TOTAL_MS && s.latencyMs in 0..TOTAL_MS && s.youtubeKbps >= 0

    private fun confirmed(s: Sample) = s.telegram == Verdict.PASS && s.youtube == Verdict.PASS
    /** Conservative measured bottleneck, not a guaranteed streaming bitrate. A missing
     * media measurement is not invented from the generic HTTPS transfer. */
    fun transferKbps(s: Sample): Long {
        val data = s.bodyBytes * 8 / s.transferMs.coerceAtLeast(1)
        return if (s.youtube == Verdict.PASS && s.youtubeKbps > 0)
            minOf(data, s.youtubeKbps.toLong()) else data
    }
    private fun score(s: Sample, purpose: Purpose): Long = when (purpose) {
        Purpose.DOWNLOAD -> transferKbps(s)
        Purpose.INTERACTIVE -> -s.latencyMs
        Purpose.BALANCED -> transferKbps(s) * 1000 / (s.latencyMs + 1000)
    }

    /** Counts adapters, not security strength. Eligibility and service proof run first.
     * Prefer fewer adapters only within 5% of the best measured score in the same
     * confirmation class. This cannot bypass explicit Tor mode or rewrite TLS/keys.
     */
    fun stages(chain: Chain): Int = listOf(chain.layout.server, chain.layout.tor, chain.layout.dpi).count { it }

    private fun bestMeasured(fresh: List<Pair<Chain, Sample>>, purpose: Purpose): Pair<Chain, Sample>? {
        if (fresh.isEmpty()) return null
        val hasConfirmed = fresh.any { confirmed(it.second) }
        val sameProof = fresh.filter { confirmed(it.second) == hasConfirmed }
        val top = sameProof.maxOf { score(it.second, purpose) }
        val comparable = sameProof.filter {
            if (purpose == Purpose.INTERACTIVE) it.second.latencyMs * 100 <= (-top) * 105
            else score(it.second, purpose) * 100 >= top * 95
        }
        return comparable.sortedWith(compareBy<Pair<Chain, Sample>> { stages(it.first) }
            .thenByDescending { score(it.second, purpose) }.thenBy { it.second.latencyMs }
            .thenBy { it.first.id }).firstOrNull()
    }

    /** Sequential transfer trials avoid self-created bandwidth competition. One absolute
     * monotonic budget includes startup, probe and close. Cold Tor is excluded; never
     * restart its bootstrap eight times on the connect button. Parent cancellation
     * propagates; the caller separately validates and applies a fresh result to live VPN.
     * open must itself clean partial startup resources if it throws or is cancelled.
     */
    suspend fun select(chains: List<Chain>, capabilities: Capabilities, purpose: Purpose,
                       elapsed: () -> Long, generation: () -> Long,
                       onTrial: ((Chain, Sample?, Long) -> Unit)? = null,
                       open: suspend (Chain) -> Session): Outcome {
        currentCoroutineContext().ensureActive()
        val epoch = generation()
        val began = elapsed()
        require(began >= 0 && began <= Long.MAX_VALUE - TOTAL_MS)
        val deadline = began + TOTAL_MS
        val diagnostics = ArrayList<String>()
        val results = ArrayList<Pair<Chain, Sample>>()
        var attempted = 0
        fun changed() = generation() != epoch
        fun networkChanged() = Outcome(State.NETWORK_CHANGED, null, null, attempted, 0, diagnostics.toList())
        for (chain in plan(chains, capabilities)) {
            currentCoroutineContext().ensureActive()
            if (changed()) return networkChanged()
            val now = elapsed()
            check(now >= began) { "Monotonic clock moved backwards" }
            val remaining = deadline - now
            if (remaining < MIN_START_MS) { diagnostics.add("deadline"); break }
            attempted++
            var closeFailed = false
            val sample = try {
                withTimeoutOrNull(minOf(PROBE_MS, remaining)) {
                    val session = open(chain)
                    try { session.measure((deadline - elapsed()).coerceAtMost(PROBE_MS).coerceAtLeast(0)) }
                    finally {
                        // Do not replace parent cancellation with a cleanup exception.
                        try { session.close() } catch (_: Exception) { closeFailed = true }
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { diagnostics.add("probe-error"); null }
            currentCoroutineContext().ensureActive()
            if (changed()) return networkChanged()
            if (closeFailed) {
                // Do not open another chain while the previous adapter may still own resources.
                diagnostics.add("cleanup-error")
                return Outcome(State.NO_ROUTE, null, null, attempted, 0, diagnostics.toList())
            }
            val finished = elapsed()
            check(finished >= now) { "Monotonic clock moved backwards" }
            if (finished <= deadline && (sample == null || (sample.chainId == chain.id && sample.generation == epoch && sample.sampledAtMs >= now)))
                onTrial?.invoke(chain,sample,finished)
            if (sample != null && sample.sampledAtMs >= now && finished <= deadline && eligible(sample, chain, epoch, finished)) results.add(chain to sample)
            else diagnostics.add(if (sample == null) "timeout-or-error" else "ineligible-or-late")
        }
        currentCoroutineContext().ensureActive()
        if (changed()) return networkChanged()
        val now = elapsed()
        check(now >= began) { "Monotonic clock moved backwards" }
        val fresh = results.filter { (c, s) -> eligible(s, c, epoch, now) }
        // Service PASS beats data-only fallback regardless of measured bandwidth.
        val best = bestMeasured(fresh, purpose)
            ?: return Outcome(State.NO_ROUTE, null, null, attempted, 0, diagnostics.toList())
        val fallback = !confirmed(best.second)
        return Outcome(if (fallback) State.FALLBACK else State.CONFIRMED,
            best.first, best.second, attempted, if (fallback) 1 else 0, diagnostics.toList())
    }
}
