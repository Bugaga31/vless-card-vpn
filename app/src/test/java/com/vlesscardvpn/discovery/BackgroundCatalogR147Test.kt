package com.vlesscardvpn

import com.vlesscardvpn.core.BackgroundCatalogQueue
import org.junit.Assert.*
import org.junit.Test

class BackgroundCatalogR147Test {
    @Test fun allThousandRoutesReceiveActualResultBeyondFirstEight() {
        val q=BackgroundCatalogQueue();val all=(0..999).map {"route-$it"};val visited=hashSetOf<String>()
        repeat(250) {
            val t=q.next("wifi|chain",all);assertEquals(4,t.routes.size)
            for(r in t.routes) {assertTrue(visited.add(r));q.completed(t,r)}
        }
        assertEquals(all.toSet(),visited);assertEquals(1000,q.snapshot("wifi|chain")!!.completed)
        assertFalse(q.pending("wifi|chain",all));assertEquals(2L,q.next("wifi|chain",all).cycle)
    }
    @Test fun issuingOrTcpHintIsNotACompletedProtocolCheck() {
        val q=BackgroundCatalogQueue();val t=q.next("wifi",(0..20).map {"r$it"})
        assertEquals(4,q.snapshot("wifi")!!.issued);assertEquals(0,q.snapshot("wifi")!!.completed)
        q.completed(t,t.routes.first());assertEquals(1,q.snapshot("wifi")!!.completed)
    }
    @Test fun fixedCloudOrderOrReorderingCannotStarveUnvisitedRoutes() {
        val q=BackgroundCatalogQueue();val all=(0..99).map {"r$it"};val seen=hashSetOf<String>()
        repeat(25) {round -> val t=q.next("wifi",if(round%2==0)all else all.reversed());t.routes.forEach {assertTrue(seen.add(it));q.completed(t,it)} }
        assertEquals(all.toSet(),seen)
    }
    @Test fun cancelledBatchDoesNotBecomePassAndReturnsNextCycle() {
        val q=BackgroundCatalogQueue();val all=(0..7).map {"r$it"};val first=q.next("wifi",all)
        val other=q.next("wifi",all);assertTrue(first.routes.intersect(other.routes.toSet()).isEmpty())
        other.routes.forEach {q.completed(other,it)};assertEquals(4,q.snapshot("wifi")!!.completed)
        val retry=q.next("wifi",all);assertEquals(first.routes,retry.routes);assertEquals(0,q.snapshot("wifi")!!.completed)
        q.completed(first,first.routes.first());assertEquals(0,q.snapshot("wifi")!!.completed)
    }
    @Test fun networkAndMaskContextsNeverShareCompletedProof() {
        val q=BackgroundCatalogQueue();val all=listOf("server|mask-a");val old=q.next("wifi",all);q.completed(old,all.first())
        val cell=q.next("cell",all);assertEquals(0,q.snapshot("cell")!!.completed)
        q.completed(old,all.first());assertEquals(0,BackgroundCatalogQueue.progress.value.completed)
        val mask=q.next("wifi",listOf("server|mask-b"));assertEquals(listOf("server|mask-b"),mask.routes)
        assertEquals(0,q.snapshot("wifi")!!.completed);assertEquals(1,cell.routes.size)
    }
    @Test fun newlyAddedRoutesAreNotHiddenBehindCompletedCycle() {
        val q=BackgroundCatalogQueue();val all=(0..3).map {"r$it"};val t=q.next("wifi",all);t.routes.forEach {q.completed(t,it)}
        assertTrue(q.pending("wifi",all+"new"));assertEquals(listOf("new"),q.next("wifi",all+"new").routes)
    }
    @Test fun deletedRoutesDoNotLeavePhantomCoverageOrAnEmptyPoolOfEight() {
        val q=BackgroundCatalogQueue();val t=q.next("wifi",listOf("a","b","c"));t.routes.forEach {q.completed(t,it)}
        q.pending("wifi",listOf("a"));assertEquals(1,q.snapshot("wifi")!!.total)
        assertTrue(q.next("empty",emptyList()).routes.isEmpty());assertEquals(0,q.snapshot("empty")!!.total)
    }
    @Test fun pausedNewNetworkDoesNotDisplayOldNetworksCompletedCount() {
        val q=BackgroundCatalogQueue();val old=q.next("wifi",listOf("a"));q.completed(old,"a")
        assertTrue(q.pending("cell",listOf("a","b")))
        assertEquals(0,BackgroundCatalogQueue.progress.value.completed)
        q.completed(old,"a");assertEquals(0,BackgroundCatalogQueue.progress.value.completed)
    }
    @Test fun callbackForUnissuedRouteCannotInventCoverage() {
        val q=BackgroundCatalogQueue();val t=q.next("wifi",listOf("a","b","c","d","e"));q.completed(t,"e")
        assertEquals(0,q.snapshot("wifi")!!.completed)
    }
    @Test fun batchBoundIsNotCatalogueBoundAndDuplicateLinksDoNotInflateProgress() {
        val q=BackgroundCatalogQueue();val t=q.next("wifi",(0..99).map {"r$it"}+"r0",1000)
        assertEquals(4,t.routes.size);assertEquals(100,q.snapshot("wifi")!!.total)
    }
}
