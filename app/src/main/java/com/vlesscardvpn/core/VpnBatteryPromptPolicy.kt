package com.vlesscardvpn.core

/** A one-time foreground offer, never a requirement for connecting or permission granted by the app. */
object VpnBatteryPromptPolicy {
    fun shouldOffer(sdk: Int, connected: Boolean, exempt: Boolean, offered: Boolean, debug: Boolean): Boolean =
        sdk >= 23 && connected && !exempt && !offered && !debug
}
