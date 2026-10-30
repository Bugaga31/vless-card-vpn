package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/** A concurrent TCP ping may update its own fields; it must not discard completed service proof.
 * Any other state change remains a fence against an older service result. */
object ProbePublication {
    fun unchangedExceptPing(current:ServerState,before:ServerState):Boolean =
        current.copy(tcpMs=before.tcpMs,checkedAt=before.checkedAt)==before
}
