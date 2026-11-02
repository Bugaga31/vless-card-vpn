package com.vlesscardvpn.core

/** Shared sensor cache only; never used to defer VPN health checks or user probes. */
object PhoneSamplingPolicy {
    const val CACHE_MS = 10_000L
    fun due(now: Long, sampledAt: Long, invalidated: Boolean): Boolean =
        invalidated || sampledAt < 0 || now < sampledAt || now - sampledAt >= CACHE_MS
}
