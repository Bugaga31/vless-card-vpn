package com.vlesscardvpn.core
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
/** Do not silently replace explicit service routing, WARP chaining, manual or Tor-only modes. */
object AutoChainLivePolicy {
    fun enabled(s: Settings) = s.mode == Mode.AUTO && s.autoChainSearch && !s.torAll &&
        !s.torRouting && s.services.isEmpty() && !s.warpChain && !s.proxyOnly
}
