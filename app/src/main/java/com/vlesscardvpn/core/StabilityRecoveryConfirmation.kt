package com.vlesscardvpn.core

/** A quality-only Telegram series is not a dead connection. Require a second fresh
 * series for the same route before starting disruptive recovery, and rate-limit
 * quality-triggered attempts across reconnects. Actual response failures bypass this. */
class StabilityRecoveryConfirmation {
    companion object { const val CONFIRM_MS=30_000L; const val COOLDOWN_MS=120_000L }
    private var key:String?=null
    private var firstAt=-1L
    private var lastAttempt=-1L

    @Synchronized fun clear() { key=null; firstAt=-1L }
    @Synchronized fun remaining(current:String,now:Long):Long {
        if (key!=current || firstAt<0 || now<firstAt) { key=current;firstAt=now }
        if(lastAttempt>=0 && now<lastAttempt) lastAttempt=now
        val confirmation=(CONFIRM_MS-(now-firstAt)).coerceAtLeast(0L)
        val cooldown=if(lastAttempt<0) 0L else (COOLDOWN_MS-(now-lastAttempt)).coerceAtLeast(0L)
        return maxOf(confirmation,cooldown)
    }
    @Synchronized fun attempted(now:Long) {lastAttempt=now;key=null;firstAt=-1L}
}
