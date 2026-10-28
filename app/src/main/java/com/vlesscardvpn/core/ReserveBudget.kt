package com.vlesscardvpn.core

/** Session-only per-network token bucket, bounded memory. Count of optional probe waves, NOT a byte limit. */
object ReserveBudget {
    private data class Bucket(var tokens:Double=2.0,var at:Long)
    private val buckets=linkedMapOf<String,Bucket>()
    @Synchronized fun take(network:String,now:Long):Boolean {
        val b=buckets.getOrPut(network){Bucket(at=now)}
        if(now<b.at) {b.tokens=2.0;b.at=now}
        b.tokens=(b.tokens+(now-b.at).coerceAtLeast(0)/60_000.0).coerceAtMost(2.0);b.at=now
        while(buckets.size>8) buckets.remove(buckets.keys.first())
        if(b.tokens<1.0) return false
        b.tokens-=1.0;return true
    }
}
