package com.vlesscardvpn.core

/** Audio is decorative: never needed for VPN, never replayed on rotation or rapid foreground changes. */
class WelcomePolicy {
    companion object {
        /** Atomic install-local marker. Survives updates/restarts, excluded from Android backup.
         * First launch is consumed even in silent mode: the voice must not unexpectedly play later.
         */
        fun claimFirstLaunch(marker: java.io.File): Boolean = runCatching { marker.createNewFile() }.getOrDefault(false)
    }
    private var last: Long? = null
    @Synchronized fun claim(now: Long, enabled: Boolean, audible: Boolean): Boolean {
        if (!enabled || !audible || now < 0) return false
        val previous = last
        if (previous != null && (now < previous || now - previous < 60_000)) return false
        last = now
        return true
    }
}
