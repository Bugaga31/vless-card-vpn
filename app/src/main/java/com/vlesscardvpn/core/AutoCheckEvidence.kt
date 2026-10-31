package com.vlesscardvpn.core
import kotlinx.coroutines.flow.MutableStateFlow
object AutoCheckEvidence {
    data class Frame(val profile:AutoCheckProfile,val limit:Int,val probe:Probe,val at:Long,val network:NetworkHandoff.Snapshot?=null,val runtimeEpoch:Long=0,val runtimeRevision:Long=0)
    val frame=MutableStateFlow<Frame?>(null)
    fun publish(profile:AutoCheckProfile,limit:Int,probe:Probe,network:NetworkHandoff.Snapshot?=null) {
        if(profile==AutoChecks.current && limit==AutoLatencyPolicy.MAX_MS)
            frame.value=Frame(profile,limit,probe,android.os.SystemClock.elapsedRealtime(),network,MaskRuntimeEvidence.state.value.epoch,MaskRuntimeEvidence.state.value.revision)
    }
    fun clear() {frame.value=null}
}
