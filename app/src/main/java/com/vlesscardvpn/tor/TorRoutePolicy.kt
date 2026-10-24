package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Settings

/** A Tor or mixed route cannot prove which selected VLESS server carried a probe. */
object TorRoutePolicy {
    fun active(settings: Settings): Boolean = settings.torAll || settings.torRouting && settings.services.isNotEmpty()
    fun canAttributeServerEvidence(torRoute: Boolean): Boolean = !torRoute
}
