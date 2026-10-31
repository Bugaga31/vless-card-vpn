package com.vlesscardvpn.core

/** Successful route checks are event-driven, not a repeating service-test timer.
 * This does not extend admission evidence freshness or hide a failure. */
class PeriodicServicePolicy {
    companion object { const val PASS_MS=90_000L; const val RETRY_MS=30_000L }
    private var key: String?=null
    private var at=-1L
    private var passed=false
    @Synchronized fun contextChanged(current:String)=key!=current
    @Synchronized fun due(current:String,now:Long,running:Boolean=false,suspectedFailure:Boolean=false):Boolean = !running &&
        (key!=current || at<0 || now<at || (!passed || suspectedFailure) && now-at>=RETRY_MS)
    @Synchronized fun completed(current:String,now:Long,success:Boolean) {key=current;at=now;passed=success}
}
