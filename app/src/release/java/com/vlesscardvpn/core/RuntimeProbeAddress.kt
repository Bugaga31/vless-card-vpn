package com.vlesscardvpn.core

/** Production never rewrites endpoints for emulator fixtures. */
object RuntimeProbeAddress {
    fun host(value: String): String = value
}
