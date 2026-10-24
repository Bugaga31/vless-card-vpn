package com.vlesscardvpn.tor

import java.io.IOException
import org.json.JSONObject

/**
 * Facade wiring [TorControl] to the [TorRotation] policy — the absorbed "brain" of
 * tor-rotator-vless. The app talks to a local Tor SOCKS5 (see [TorCard]); this class
 * rotates the exit through the authenticated ControlPort and feeds measured latencies
 * back into the adaptive policy.
 *
 * Replaced the rotator project's shell scripts and hardcoded schedules with:
 *  - adaptive per-country rotation thresholds with raw-latency floors,
 *  - blacklisting slow exits (real exit countries, measured via [TorProbes]),
 *  - CI-friendly policy snapshots ([TorRotation.toJson]).
 */
class TorRotator(
    private val control: TorControl,
    private val rotation: TorRotation = TorRotation(),
    /** Restrict exits to these ISO countries; empty = worldwide NEWNYM rotation. */
    private val countries: List<String> = emptyList(),
    private val cookieHex: String,
) {
    private var connected = false
    private var lastError: String? = null

    val lastErrorOr: String? get() = lastError

    fun connect() {
        try {
            control.connect(cookieHex)
            if (countries.isNotEmpty()) control.setExitCountries(countries)
            // Seed the cadence clock at connect: the first rotation lands after the
            // interval, not instantly — the freshly started Tor already has a circuit.
            rotation.markRotated(System.currentTimeMillis())
            connected = true
            lastError = null
        } catch (e: IOException) {
            lastError = e.message
        }
    }

    /** Feed a measured exit (from [TorProbes.exitInfo]) into the policy. */
    fun recordExit(latencyMs: Int, country: String, transport: TorRotation.Transport = TorRotation.Transport.DIRECT) {
        rotation.record(country, latencyMs.toLong(), transport)
    }

    fun setOnline(value: Boolean) = rotation.setOnline(value)

    /**
     * Call periodically (e.g. every 10 s). Returns true when a rotation happened.
     * Full rotator algorithm: cadence gate → pick the best-measured exit country →
     * pin it on the ControlPort (SETCONF ExitNodes) → fresh circuits (NEWNYM).
     * Silent no-op when offline, not connected, or rotation is not due.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!connected) return false
        if (!rotation.shouldRotate(nowMs)) return false
        return try {
            rotation.pick(nowMs)?.let { best ->
                control.setExitCountries(listOf(best))
                rotation.notePicked(best)
            }
            control.signalNewNym()
            rotation.markRotated(nowMs)
            true
        } catch (e: IOException) {
            lastError = e.message
            false
        }
    }

    fun stats(): JSONObject = rotation.statsSnapshot()

    fun close() = control.close()
}
