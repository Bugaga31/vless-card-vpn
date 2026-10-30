package com.vlesscardvpn.core
import kotlinx.coroutines.flow.MutableStateFlow
object AutoCheckEvidence {
    data class Frame(val profile:AutoCheckProfile,val limit:Int,val probe:Probe,val at:Long)
    val frame=MutableStateFlow<Frame?>(null)
    fun publish(profile:AutoCheckProfile,limit:Int,probe:Probe) {
        if(profile==AutoChecks.current && limit==AutoLatencyPolicy.MAX_MS)
            frame.value=Frame(profile,limit,probe,android.os.SystemClock.elapsedRealtime())
    }
    fun clear() {frame.value=null}
}
