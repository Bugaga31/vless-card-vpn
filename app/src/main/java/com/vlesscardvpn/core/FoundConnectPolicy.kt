package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Working is the owner's tested list. Missing current proof requests revalidation, not invisibility. */
object FoundConnectPolicy {
    const val LIMIT=64
    fun shortlist(servers:List<Server>,state:(Server)->ServerState)=shortlist(servers,state){false}
    /** Preserve fresh admission proof before the shortlist cap, not just old HTTPS timing. */
    fun shortlist(servers:List<Server>,state:(Server)->ServerState,ready:(Server)->Boolean)=servers.distinctBy {it.id}
        .filter {AutoEligibility.vlessServer(it) && state(it).works}
        .sortedWith(compareBy<Server>{!ready(it)}.thenBy{state(it).realMs}).take(LIMIT)
    fun count(servers:List<Server>,state:(Server)->ServerState)=servers.distinctBy {it.id}
        .count {AutoEligibility.vlessServer(it) && state(it).works}
    fun enabled(automatic:Boolean,tor:Boolean,connected:Boolean,count:Int)=automatic && !connected && count>0
}
