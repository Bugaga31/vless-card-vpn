package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings

/** The Home Tor choice uses Tor independently of a VLESS catalogue. User exceptions remain explicit. */
object TorConnectionPolicy {
    fun selectTor(s: Settings): Settings = s.copy(torAll = true, torDirect = true)
    fun selectMode(s: Settings, mode: Mode): Settings = s.copy(mode = mode, torAll = false)
    fun runtime(s: Settings): Settings = if (s.torAll) s.copy(mode = Mode.BYEDPI) else s
    fun needsAutoSearch(s: Settings): Boolean = s.mode == Mode.AUTO && !s.torAll
    fun rotation(s: Settings, now: () -> Long): TorRotation = TorRotation(
        rotateEveryMs = s.torRotationSeconds.coerceIn(30, 86400) * 1000L,
        periodic = true, fastOnly = s.torFastOnly,
        ceilingMs = if (s.torFastOnly) s.torMaxLatencyMs.coerceAtLeast(1).toLong() else 7000L,
        now = now)
}
