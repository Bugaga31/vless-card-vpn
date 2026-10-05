package com.vlesscardvpn.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared VPN status (service and UI live in one process). */
object Tunnel {
    enum class State { IDLE, CONNECTING, CONNECTED, ERROR }
    data class Status(
        val state: State = State.IDLE,
        val message: String = "",
        val route: String = "",           // what carries the traffic, e.g. "3 сервера · самый быстрый"
        val check: String = "",           // result of the end-to-end check through 127.0.0.1:socks
        val checkOk: Boolean? = null,
        val since: Long = 0,
    )
    val status = MutableStateFlow(Status())
    @Volatile var byeDpiPort: Int? = null
    /** Running VPN's local SOCKS listener (random port + login in stealth mode); null when disconnected. */
    @Volatile var socks: com.vlesscardvpn.xray.SocksAuth? = null
    @Volatile var dpiLabel: String = ""
}
