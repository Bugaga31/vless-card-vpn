package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.core.ServerOnlyPolicy

/** Tor is an exit path behind a measured VLESS upstream; it never replaces the server. */
object TorConnectionPolicy {
    fun selectTor(s: Settings): Settings = s.copy(mode = Mode.AUTO, torAll = true, torDirect = false,
        torNetworkRecommendations = true)
    fun selectMode(s: Settings, mode: Mode): Settings = s.copy(mode = if (mode == Mode.BYEDPI || mode == Mode.HYBRID) Mode.AUTO else mode, torAll = false)
    fun runtime(s: Settings): Settings = ServerOnlyPolicy.normalize(s)
    fun needsAutoSearch(s: Settings): Boolean = ServerOnlyPolicy.normalize(s).mode == Mode.AUTO
    fun rotation(s: Settings, now: () -> Long): TorRotation = TorRotation(
        rotateEveryMs = s.torRotationSeconds.coerceIn(30, 86400) * 1000L,
        periodic = true, fastOnly = s.torFastOnly,
        ceilingMs = if (s.torFastOnly) s.torMaxLatencyMs.coerceAtLeast(1).toLong() else 7000L,
        now = now)
}
