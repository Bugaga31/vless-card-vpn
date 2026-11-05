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
    /**
     * 1.2.136: the same live network (same handle and learning profile) whose path only GAINED elements — an IPv6
     * prefix arriving after IPv4, a second address. Established connections are untouched by an addition, so it is
     * accepted in place: no new revision, in-flight work stays valid, nothing is cancelled or reconnected.
     * Anything else (a lost or replaced element, another handle/profile, presence) still goes through [update].
     */
    @Synchronized fun adopt(snapshot: Snapshot?): Boolean {
        val previous = current ?: return false
        if (snapshot == null || snapshot == previous) return false
        if (snapshot.handle != previous.handle || snapshot.profile != previous.profile) return false
        if (!NetworkPath.extends(previous.path, snapshot.path)) return false
        current = snapshot
        return true
    }
    @Synchronized fun valid(ticket: Long) = revision == ticket && current != null
    @Synchronized fun reset() { current = null; revision++ }
}
