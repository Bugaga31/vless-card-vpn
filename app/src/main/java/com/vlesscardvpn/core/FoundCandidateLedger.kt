package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Accept only the exact candidate tested by this operation. A locally acquired pin may
 * replace the empty pin, but never credentials, transport, SNI or an existing pin. */
class FoundCandidateLedger(candidates:List<Server>) {
    private val expected=candidates.associateBy {it.id}.toMutableMap()
    /** Preparation records exact local pin acquisition, not successful service admission. */
    fun prepared(server:Server)=verified(server)
    @Synchronized fun verified(server:Server) {
        val old=expected[server.id] ?: return
        if(server==old || ForegroundProbePlan.needsCertificate(old) && server.pcs.isNotBlank() &&
            server.copy(pcs=old.pcs)==old) expected[server.id]=server
    }
    @Synchronized fun current(servers:List<Server>):List<Server> {
        val exact=servers.toSet()
        return expected.values.filter {it in exact}
    }
    companion object {
        /** Compare-and-set: pin acquisition must not overwrite an edited endpoint. */
        fun applyPin(current:Server,original:Server,pinned:Server):Server =
            if(current==original && ForegroundProbePlan.needsCertificate(original) && pinned.pcs.isNotBlank() &&
                pinned.copy(pcs=original.pcs)==original) pinned else current
    }
}
