package com.vlesscardvpn.core

/** Pure, bounded network revision gate. Duplicate capability events never cause restart loops. */
class NetworkHandoff {
    data class Snapshot(val handle: String, val profile: String, val path: String = "")
    data class Change(val revision: Long, val online: Boolean,
        val handleChanged: Boolean = false, val profileChanged: Boolean = false,
        val pathChanged: Boolean = false, val presenceChanged: Boolean = false)
    private var current: Snapshot? = null
    private var revision = 0L
    @Synchronized fun seed(snapshot: Snapshot?) { current = snapshot; revision++ }
    @Synchronized fun update(snapshot: Snapshot?): Change? {
        if (snapshot == current) return null
        val previous = current
        current = snapshot; revision++
        return Change(revision, snapshot != null,
            previous?.handle != snapshot?.handle, previous?.profile != snapshot?.profile,
            previous?.path != snapshot?.path, (previous == null) != (snapshot == null))
    }
    @Synchronized fun valid(ticket: Long) = revision == ticket && current != null
    @Synchronized fun reset() { current = null; revision++ }
}
