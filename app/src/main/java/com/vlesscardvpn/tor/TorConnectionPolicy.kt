package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.core.ServerOnlyPolicy

/** Tor is an exit path behind a measured VLESS upstream; it never replaces the server. */
object TorConnectionPolicy {
    /** User-requested mandatory bridge policy. Existing valid transport/lines are preserved. */
    fun bridged(s: Settings): Settings = if (s.torAll || s.torRouting || s.torAuto || s.torRotation)
        s.copy(torBridge=if(s.torBridge in setOf("obfs4","webtunnel")) s.torBridge else "obfs4",torDirect=false)
        else s
    fun selectTor(s: Settings): Settings = bridged(s.copy(mode = Mode.AUTO, torAll = true, torDirect = false,
        torNetworkRecommendations = true, torRotation = true, torFastOnly = true))
    /** Explicit one-tap preset: mandatory cached/imported bridges behind the VLESS upstream. */
    fun automatic(s: Settings): Settings = selectTor(s)
    fun selectMode(s: Settings, mode: Mode): Settings = s.copy(mode = if (mode == Mode.BYEDPI || mode == Mode.HYBRID) Mode.AUTO else mode, torAll = false)
    fun runtime(s: Settings): Settings = bridged(ServerOnlyPolicy.normalize(s))
    fun needsAutoSearch(s: Settings): Boolean = ServerOnlyPolicy.normalize(s).mode == Mode.AUTO
    fun rotation(s: Settings, now: () -> Long): TorRotation = TorRotation(
        rotateEveryMs = TorCountryPolicy.interval(s.torRotationSeconds),
        periodic = true, fastOnly = s.torFastOnly,
        ceilingMs = if (s.torFastOnly) s.torMaxLatencyMs.coerceAtLeast(1).toLong() else 7000L,
        now = now)
}
