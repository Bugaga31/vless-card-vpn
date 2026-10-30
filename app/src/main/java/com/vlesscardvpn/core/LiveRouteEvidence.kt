package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/** Publish only attributed fresh measurements. A new failure must erase the old green proof. */
object LiveRouteEvidence {
    fun commit(old:ServerState,p:Probe,profile:AutoCheckProfile,net:String,mask:String,started:Long,now:Long):ServerState {
        if(old.checkedAt>started || old.maskFor(net)!=mask) return old
        val key=AutoChecks.stamp(p,profile,AutoLatencyPolicy.MAX_MS)
        return old.copy(realMs=p.realMs,tgOk=p.tgOk,tgMs=p.tgMs,ytOk=p.ytOk,checkedAt=now,
            autoChecksKey=key,autoChecksAt=if(key.isNotEmpty()) now else 0,
            serviceNetwork=net,serviceMask=mask,serviceCheckedAt=now)
    }
}
