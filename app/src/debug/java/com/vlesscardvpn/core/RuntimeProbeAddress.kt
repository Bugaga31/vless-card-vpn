package com.vlesscardvpn.core

/** Exact closed-fixture alias mapping, shared by TCP prefilter and native debug config. */
object RuntimeProbeAddress {
    fun host(value: String): String =
        if (Regex("^r163-pool-[0-7]\\.test$").matches(value)) "10.0.2.2" else value
}
