package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class HandoffDiagnosticsR242Test {
    private val a=NetworkHandoff.Snapshot("one","profile","path")
    @Test fun duplicateStillDoesNotReconnect() {val h=NetworkHandoff();h.seed(a);assertNull(h.update(a.copy()))}
    @Test fun pathOnlyChangeRemainsARealRevision() {
        val h=NetworkHandoff();h.seed(a);val c=h.update(a.copy(path="new"))!!
        assertTrue(c.pathChanged);assertFalse(c.handleChanged);assertFalse(c.profileChanged);assertFalse(c.presenceChanged);assertTrue(h.valid(c.revision))
    }
    @Test fun profileChangeIsNotMistakenForHandleChange() {val h=NetworkHandoff();h.seed(a);val c=h.update(a.copy(profile="new"))!!;assertTrue(c.profileChanged);assertFalse(c.handleChanged)}
    @Test fun offlineRevisionInvalidatesOldTicket() {val h=NetworkHandoff();h.seed(a);val c=h.update(a.copy(handle="two"))!!;val lost=h.update(null)!!;assertFalse(lost.online);assertTrue(lost.presenceChanged);assertFalse(h.valid(c.revision));assertFalse(h.valid(lost.revision))}
    @Test fun initialAppearanceIsExplicit() {val h=NetworkHandoff();h.seed(null);val c=h.update(a)!!;assertTrue(c.presenceChanged);assertTrue(c.online)}
}
