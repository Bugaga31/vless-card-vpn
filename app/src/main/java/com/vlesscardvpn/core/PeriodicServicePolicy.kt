package com.vlesscardvpn.core

/** Completed full-service probes have a short lease. A light liveness probe never renews it. */
class PeriodicServicePolicy {
    companion object { const val PASS_MS=90_000L; const val RETRY_MS=30_000L }
    private var key: String?=null
    private var at=-1L
    private var passed=false
    @Synchronized fun due(current:String,now:Long,running:Boolean=false):Boolean = !running &&
        (key!=current || at<0 || now<at || now-at>=if(passed) PASS_MS else RETRY_MS)
    @Synchronized fun completed(current:String,now:Long,success:Boolean) {key=current;at=now;passed=success}
}
