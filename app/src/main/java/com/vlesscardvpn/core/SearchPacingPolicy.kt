package com.vlesscardvpn.core

/** Only discovery pacing; never drops a live VPN or weakens service acceptance. */
object SearchPacingPolicy {
    /** Explicit connect search keeps a five-second inter-wave pause in every power profile.
     * Heat/call/battery protection still caps foreground probe concurrency in PowerPolicy;
     * optional background work retains its own independent deferral rules.
     */
    @Suppress("UNUSED_PARAMETER")
    fun rest(plan:PowerPolicy.Plan):Long = AutoSearchCampaign.WAVE_PAUSE_MS

}

/** A live connection is not a benchmark toy: unknown or marginal gains never restart it. */
object BoostDecision {
    fun shouldReplace(connected:Boolean,current:String?,candidate:String,currentKbps:Int,candidateKbps:Int):Boolean {
        if(candidateKbps<=0 || candidate==current) return false
        if(!connected) return true
        if(current==null || currentKbps<=0) return false
        return candidateKbps.toLong()>currentKbps.toLong()*13/10+500
    }
}
