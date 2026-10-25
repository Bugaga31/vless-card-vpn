package com.vlesscardvpn.core
import com.vlesscardvpn.core.AutoChainSelection as A
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class AutoChainCooldownTest {
    private fun chain(key:String="a",layout:A.Layout=A.Layout.TOR_SERVER)=A.Chain(layout,serverKey=if(layout.server)key else "",torProfileKey=if(layout.tor)"bridge" else "")
    @Test fun `explicit failure quarantines ten minutes then permits fresh trial`() {
        val q=AutoChainCooldown();val c=chain();q.record(c,"net",1000,false,true)
        assertFalse(q.allowed(c,"net",600999));assertTrue(q.allowed(c,"net",601000))
    }
    @Test fun `unknown is not immediately called failure but repeated unknown cools down`() {
        val q=AutoChainCooldown();val c=chain();q.record(c,"net",1000,false);assertTrue(q.allowed(c,"net",1001))
        q.record(c,"net",2000,false);assertFalse(q.allowed(c,"net",2001))
    }
    @Test fun `successful fresh measurement clears strikes`() {
        val q=AutoChainCooldown();val c=chain();q.record(c,"net",1000,false);q.record(c,"net",2000,true);q.record(c,"net",3000,false)
        assertTrue(q.allowed(c,"net",3001))
    }
    @Test fun `network and bridge server mask identities isolated`() {
        val q=AutoChainCooldown();val c=chain();q.record(c,"net",1000,false,true)
        assertTrue(q.allowed(c,"other",1001));assertTrue(q.allowed(chain("b"),"net",1001))
        assertTrue(q.allowed(c.copy(torProfileKey="bridge2"),"net",1001));assertTrue(q.allowed(c.copy(maskKey="other"),"net",1001))
    }
    @Test fun `standalone Tor and Tor server have distinct cooldown`() {
        val q=AutoChainCooldown();q.record(chain(),"net",1000,false,true);assertTrue(q.allowed(chain(layout=A.Layout.TOR),"net",1001))
    }
    @Test fun `native server not quarantined by Tor policy`() {
        val q=AutoChainCooldown();val c=chain(layout=A.Layout.SERVER);q.record(c,"net",1000,false,true);assertTrue(q.allowed(c,"net",1001));assertEquals(0,q.size())
    }
    @Test fun `clock rollback does not create permanent ban`() {
        val q=AutoChainCooldown();val c=chain();q.record(c,"net",1000,false,true);assertTrue(q.allowed(c,"net",999))
    }
    @Test fun `old isolated unknown does not accumulate forever`() {
        val q=AutoChainCooldown(100);val c=chain();q.record(c,"net",1000,false);q.record(c,"net",1100,false);assertTrue(q.allowed(c,"net",1101))
    }
    @Test fun `bounded map cannot grow with catalogue size`() {
        val q=AutoChainCooldown(capacity=2);(1..10).forEach {q.record(chain("s$it"),"net",1000,false,true)};assertEquals(2,q.size())
    }
    @Test fun `network generation change never emits trial failure`() = runBlocking {
        var generation=1L;var called=false
        A.select(listOf(chain(layout=A.Layout.SERVER)),A.Capabilities(setOf(A.Layout.SERVER)),A.Purpose.BALANCED,{1000L},{generation},onTrial={_,_,_->called=true}) {
            generation=2;object:A.Session {override suspend fun measure(remainingMs:Long):A.Sample = error("synthetic failure");override fun close(){}}
        }
        assertFalse(called)
    }
    @Test fun `cancellation never emits trial failure`() = runBlocking {
        var called=false
        val job=launch(start=CoroutineStart.UNDISPATCHED) {
            A.select(listOf(chain(layout=A.Layout.SERVER)),A.Capabilities(setOf(A.Layout.SERVER)),A.Purpose.BALANCED,{1000L},{1L},onTrial={_,_,_->called=true}) {
                awaitCancellation()
            }
        };job.cancelAndJoin();assertFalse(called)
    }
}
