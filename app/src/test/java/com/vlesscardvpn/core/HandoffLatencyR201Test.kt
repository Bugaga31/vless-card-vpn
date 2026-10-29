package com.vlesscardvpn
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test
class HandoffLatencyR201Test {
 private fun s(n:String)=Server(n,"vless","$n.test",443,"key",source=n)
 @Test fun firstCallbackWaitsOneHundred(){assertEquals(100L,NetworkRefreshWindow().delayMs(0))}
 @Test fun burstKeepsShortQuietPeriod(){val w=NetworkRefreshWindow();w.delayMs(0);assertEquals(100L,w.delayMs(50))}
 @Test fun nearDeadlineUsesRemainingOnly(){val w=NetworkRefreshWindow();w.delayMs(0);assertEquals(30L,w.delayMs(220))}
 @Test fun deadlineCannotBePushedByCallbacks(){val w=NetworkRefreshWindow();w.delayMs(0);assertEquals(0L,w.delayMs(250));assertEquals(0L,w.delayMs(1000))}
 @Test fun resetStartsNewIndependentWindow(){val w=NetworkRefreshWindow();w.delayMs(0);w.reset();assertEquals(100L,w.delayMs(1000))}
 @Test fun clockRollbackStartsNewWindow(){val w=NetworkRefreshWindow();w.delayMs(1000);assertEquals(100L,w.delayMs(20));assertEquals(0L,w.delayMs(270))}
 @Test fun callbackStormBounded(){val w=NetworkRefreshWindow();for(t in 0L..500L){val d=w.delayMs(t);assertTrue(d in 0..100);assertTrue(t+d<=250 || d==0L)}}
 @Test fun repeatedResetIsSafe(){val w=NetworkRefreshWindow();repeat(10){w.reset()};assertEquals(100L,w.delayMs(0))}
 @Test fun deadlineConstantsAreBounded(){assertEquals(100L,NetworkRefreshWindow.QUIET_MS);assertEquals(250L,NetworkRefreshWindow.MAX_WAIT_MS)}
 @Test fun returnedNetworkRouteGetsFirstSlot(){val a=s("old");val b=s("cell");assertEquals(listOf(b,a),AutoHandoffPolicy.prioritize(listOf(a,b),setOf(b.id)))}
 @Test fun multipleRememberedRoutesKeepRelativeOrder(){val a=s("a");val b=s("b");val c=s("c");assertEquals(listOf(b,c,a),AutoHandoffPolicy.prioritize(listOf(a,b,c),setOf(c.id,b.id)))}
 @Test fun unknownNetworkPreservesCandidateOrder(){val c=listOf(s("a"),s("b"));assertEquals(c,AutoHandoffPolicy.prioritize(c,emptySet()))}
 @Test fun removedRememberedEndpointCannotReappear(){val a=s("a");assertEquals(listOf(a),AutoHandoffPolicy.prioritize(listOf(a),setOf(s("removed").id)))}
 @Test fun duplicateIdsDoNotConsumeProbeSlots(){val a=s("a");assertEquals(listOf(a),AutoHandoffPolicy.prioritize(listOf(a,a),setOf(a.id)))}
 @Test fun fastQueueBoundedAtEight(){assertEquals(8,AutoHandoffPolicy.prioritize((1..100).map {s("s$it")},emptySet()).size)}
 @Test fun emptyFastQueueIsSafe(){assertTrue(AutoHandoffPolicy.prioritize(emptyList(),setOf("missing")).isEmpty())}
 @Test fun probeDeadlineLeavesCleanupRoom(){assertEquals(4500L,AutoHandoffPolicy.PROBE_WINDOW_MS);assertEquals(6500L,AutoHandoffPolicy.TOTAL_MS);assertTrue(AutoHandoffPolicy.PROBE_WINDOW_MS<AutoHandoffPolicy.TOTAL_MS)}
 @Test fun duplicateNetworkEventsDoNotCreateNewRevision(){val g=NetworkHandoff();val n=NetworkHandoff.Snapshot("1","cell","p");g.seed(n);assertNull(g.update(n))}
 @Test fun sameHandleChangedPathInvalidatesOldRevision(){val g=NetworkHandoff();g.seed(null);val c=g.update(NetworkHandoff.Snapshot("1","cell","a"))!!;g.update(NetworkHandoff.Snapshot("1","cell","b"));assertFalse(g.valid(c.revision))}
 @Test fun stalePathCannotQualifyNewRoute(){assertFalse(NetworkObservation.accepts(NetworkHandoff.Snapshot("1","cell","a"),NetworkHandoff.Snapshot("1","cell","b")))}
}
