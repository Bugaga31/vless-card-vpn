package com.vlesscardvpn.domain

/** Narrow workaround for executed API 26 TUN failures. Other APIs keep the existing stack. */
object TunStackPolicy {
    fun forSdk(sdk: Int?): String = if (sdk == 26) "gvisor" else "mixed"
}
