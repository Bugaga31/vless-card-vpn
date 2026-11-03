package com.vlesscardvpn.core

/** Pure, bounded network revision gate. Duplicate capability events never cause restart loops. */
class NetworkHandoff {
    data class Snapshot(val handle: String, val profile: String, val path: String = "")
    data class Change(val revision: Long, val online: Boolean)
    private var current: Snapshot? = null
    private var revision = 0L
    @Synchronized fun seed(snapshot: Snapshot?) { current = snapshot; revision++ }
    @Synchronized fun update(snapshot: Snapshot?): Change? {
        if (snapshot == current) return null
        current = snapshot; revision++
        return Change(revision, snapshot != null)
    }
    @Synchronized fun valid(ticket: Long) = revision == ticket && current != null
    @Synchronized fun reset() { current = null; revision++ }
}
