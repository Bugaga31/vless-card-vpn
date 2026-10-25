package com.vlesscardvpn.core

import com.vlesscardvpn.core.AutoChainSelection.Capabilities
import com.vlesscardvpn.core.AutoChainSelection.Layout
import com.vlesscardvpn.core.AutoChainSelection.Chain
import com.vlesscardvpn.core.AutoChainSelection.Sample
import com.vlesscardvpn.core.AutoChainSelection.Session
import com.vlesscardvpn.core.AutoChainSelection.Verdict
import com.vlesscardvpn.core.AutoChainSelection.Purpose
import com.vlesscardvpn.core.AutoChainSelection.State
import com.vlesscardvpn.core.AutoChainSelection as A
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Policy/driver-contract tests with synthetic sessions, NOT real Tor or phone tests. */
class AutoChainSelectionTest {
    private val caps = Capabilities(Layout.values().toSet(), torOptIn = true, torReady = true)
    private fun chain(key: String = "a", layout: Layout = Layout.SERVER, tcp: Boolean = true) = Chain(layout,
        serverKey = if (layout.server) key else "", torProfileKey = if (layout.tor) "bridge1" else "",
        dpiKey = if (layout.dpi) "fixed1" else "", tcpServer = tcp)
    private fun sample(c: Chain, at: Long = 1000, generation: Long = 1,
                       tg: Verdict = Verdict.PASS, yt: Verdict = Verdict.PASS,
                       bytes: Long = 32_768, transfer: Long = 100, latency: Long = 40) =
        Sample(c.id, generation, at, Verdict.PASS, tg, yt, true, bytes, transfer, latency)
    private fun session(s: Sample, closed: () -> Unit = {}): Session = object : Session {
        override suspend fun measure(remainingMs: Long) = s
        override fun close() = closed()
    }
    @Test fun `unknown adapters are never planned`() { assertTrue(A.plan(listOf(chain()), caps.copy(implemented = emptySet())).isEmpty()) }
    @Test fun `Tor requires explicit opt in`() { assertEquals("tor-not-opted-in", A.incompatible(chain(layout=Layout.TOR), caps.copy(torOptIn=false))) }
    @Test fun `cold Tor is not bootstrapped on every trial`() { assertEquals("tor-not-ready", A.incompatible(chain(layout=Layout.TOR), caps.copy(torReady=false))) }
    @Test fun `Tor rejects UDP-only purpose`() { assertEquals("tor-has-no-udp", A.incompatible(chain(layout=Layout.TOR_SERVER), caps.copy(requiresUdp=true))) }
    @Test fun `Tor cannot carry WireGuard or Hy2 UDP endpoints`() { assertEquals("requires-tcp-server", A.incompatible(chain(layout=Layout.TOR_SERVER,tcp=false),caps)) }
    @Test fun `ByeDPI chain cannot carry UDP endpoint`() { assertEquals("requires-tcp-server", A.incompatible(chain(layout=Layout.DPI_SERVER,tcp=false),caps)) }
    @Test fun `disallowed exit never becomes fallback`() { assertEquals("disallowed-server-exit", A.incompatible(chain().copy(serverExitAllowed=false),caps)) }
    @Test fun `direction is part of evidence identity`() { assertNotEquals(chain(layout=Layout.SERVER_TOR).id, chain(layout=Layout.TOR_SERVER).id) }
    @Test fun `Tor bridge and DPI strategy changes invalidate identity`() {
        val c=chain(layout=Layout.DPI_TOR_SERVER)
        assertNotEquals(c.id,c.copy(torProfileKey="bridge2").id)
        assertNotEquals(c.id,c.copy(dpiKey="fixed2").id)
        assertNotEquals(c.id,c.copy(maskKey="different").id)
    }
    @Test fun `deduplicate and cap eight chains`() {
        val candidates=(1..20).map { chain("s$it") }
        assertEquals(8,A.plan(candidates+candidates,caps).size)
        assertEquals(1,A.plan(listOf(chain(),chain()),caps).size)
    }
    @Test fun `invalid configuration key cannot inject directives`() {
        try { chain("a\nSocks5Proxy"); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun `Tor layout cannot omit profile`() {
        try { Chain(Layout.TOR); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun `data proof required not just TCP latency`() {
        val c=chain();val s=sample(c)
        assertFalse(A.eligible(s.copy(verifiedBody=false),c,1,1000))
        assertFalse(A.eligible(s.copy(data=Verdict.UNKNOWN),c,1,1000))
        assertFalse(A.eligible(s.copy(bodyBytes=0),c,1,1000))
    }
    @Test fun `explicit Telegram failure excludes chain`() { val c=chain();assertFalse(A.eligible(sample(c,tg=Verdict.FAIL),c,1,1000)) }
    @Test fun `explicit YouTube failure excludes chain`() { val c=chain();assertFalse(A.eligible(sample(c,yt=Verdict.FAIL),c,1,1000)) }
    @Test fun `stale future and other network evidence rejected`() {
        val c=chain();val s=sample(c)
        assertFalse(A.eligible(s,c,2,1000));assertFalse(A.eligible(s,c,1,999));assertFalse(A.eligible(s,c,1,61_001))
    }
    @Test fun `standalone server proof cannot certify Tor chain`() { val c=chain();assertFalse(A.eligible(sample(c),chain(layout=Layout.TOR_SERVER),1,1000)) }
    @Test fun `invalid metrics and overlarge body rejected`() {
        val c=chain();val s=sample(c)
        listOf(s.copy(bodyBytes=Long.MAX_VALUE),s.copy(transferMs=0),s.copy(latencyMs=-1)).forEach { assertFalse(A.eligible(it,c,1,1000)) }
    }
    @Test fun `faster proven chain wins instead of first responding`() = runBlocking {
        val a=chain("a");val b=chain("b")
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{1}) { session(sample(it,transfer=if(it==a)200 else 50)) }
        assertEquals(b,r.selected);assertEquals(State.CONFIRMED,r.state);assertEquals(0,r.fallbackCount)
    }
    @Test fun `within five percent prefer fewer adapters for throughput and balanced`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        for(purpose in listOf(Purpose.DOWNLOAD,Purpose.BALANCED)) {
            val result=A.select(listOf(layered,simple),caps,purpose,{1000},{1}) {
                session(sample(it,transfer=if(it==simple)104 else 100))
            }
            assertEquals(simple,result.selected)
        }
    }
    @Test fun `materially faster layered route can still win`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        val result=A.select(listOf(simple,layered),caps,Purpose.DOWNLOAD,{1000},{1}) {
            session(sample(it,transfer=if(it==simple)200 else 100))
        }
        assertEquals(layered,result.selected)
    }
    @Test fun `simple fallback never beats a confirmed layered route`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        val result=A.select(listOf(simple,layered),caps,Purpose.DOWNLOAD,{1000},{1}) {
            session(sample(it,tg=if(it==simple)Verdict.UNKNOWN else Verdict.PASS))
        }
        assertEquals(layered,result.selected);assertEquals(State.CONFIRMED,result.state)
    }
    @Test fun `simple explicit service failure is never preferred`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        val result=A.select(listOf(simple,layered),caps,Purpose.DOWNLOAD,{1000},{1}) {
            session(sample(it,yt=if(it==simple)Verdict.FAIL else Verdict.PASS))
        }
        assertEquals(layered,result.selected)
    }
    @Test fun `interactive comparable latency also prefers fewer adapters`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        val result=A.select(listOf(layered,simple),caps,Purpose.INTERACTIVE,{1000},{1}) {
            session(sample(it,latency=if(it==simple)104 else 100))
        }
        assertEquals(simple,result.selected)
    }
    @Test fun `interactive significant latency advantage still wins`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        val result=A.select(listOf(simple,layered),caps,Purpose.INTERACTIVE,{1000},{1}) {
            session(sample(it,latency=if(it==simple)200 else 100))
        }
        assertEquals(layered,result.selected)
    }
    @Test fun `complexity is adapter count not a claim about encryption strength`() {
        assertEquals(1,A.stages(chain()));assertEquals(2,A.stages(chain(layout=Layout.DPI_SERVER)))
        assertEquals(3,A.stages(chain(layout=Layout.DPI_TOR_SERVER)))
    }
    @Test fun `zero latency cannot be replaced with a slower simple route`() = runBlocking {
        val simple=chain("simple");val layered=chain("layered",Layout.DPI_SERVER)
        val result=A.select(listOf(simple,layered),caps,Purpose.INTERACTIVE,{1000},{1}) {
            session(sample(it,latency=if(it==simple)1 else 0))
        }
        assertEquals(layered,result.selected)
    }
    @Test fun `slow measured video cannot hide behind fast generic download`() = runBlocking {
        val a=chain("a"); val b=chain("b")
        for (purpose in listOf(Purpose.DOWNLOAD,Purpose.BALANCED)) {
            val result=A.select(listOf(a,b),caps,purpose,{1000},{1}) {
                session(sample(it,transfer=if(it==a)10 else 100).copy(youtubeKbps=if(it==a)128 else 2000))
            }
            assertEquals(b,result.selected); assertEquals(State.CONFIRMED,result.state)
        }
    }
    @Test fun `media measurement cannot inflate actual generic transfer rate`() {
        val s=sample(chain(),transfer=100).copy(youtubeKbps=Int.MAX_VALUE)
        assertEquals(2621L,A.transferKbps(s))
    }
    @Test fun `unknown media speed is not treated as measured video evidence`() {
        val s=sample(chain(),yt=Verdict.UNKNOWN,transfer=100).copy(youtubeKbps=1)
        assertEquals(2621L,A.transferKbps(s))
        assertEquals(Verdict.UNKNOWN,s.youtube)
    }
    @Test fun `missing media metric preserves legacy rate without manufacturing PASS`() {
        val s=sample(chain(),yt=Verdict.UNKNOWN,transfer=100)
        assertEquals(2621L,A.transferKbps(s)); assertEquals(0,s.youtubeKbps)
    }
    @Test fun `negative media metric is ineligible`() {
        val c=chain(); assertFalse(A.eligible(sample(c).copy(youtubeKbps=-1),c,1,1000))
    }
    @Test fun `interactive purpose favors latency over bandwidth`() = runBlocking {
        val a=chain("a");val b=chain("b")
        val r=A.select(listOf(a,b),caps,Purpose.INTERACTIVE,{1000},{1}) { session(sample(it,latency=if(it==a)20 else 400,transfer=if(it==a)200 else 20)) }
        assertEquals(a,r.selected)
    }
    @Test fun `fallback deferred until all bounded trials finish`() = runBlocking {
        val a=chain("a");val b=chain("b");var closed=0
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{1}) { session(sample(it,tg=if(it==a)Verdict.UNKNOWN else Verdict.PASS,transfer=if(it==a)10 else 300), {closed++}) }
        assertEquals(b,r.selected);assertEquals(2,r.attempted);assertEquals(2,closed);assertEquals(0,r.fallbackCount)
    }
    @Test fun `only one honest fallback at finalization`() = runBlocking {
        val a=chain("a");val b=chain("b")
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{1}) { session(sample(it,tg=Verdict.UNKNOWN,yt=Verdict.UNKNOWN)) }
        assertEquals(State.FALLBACK,r.state);assertEquals(1,r.fallbackCount);assertEquals(Verdict.UNKNOWN,r.sample!!.telegram)
    }
    @Test fun `a confirmed earlier result beats a faster later fallback`() = runBlocking {
        val a=chain("a");val b=chain("b")
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{1}) { session(sample(it,yt=if(it==b)Verdict.UNKNOWN else Verdict.PASS,transfer=if(it==b)1 else 500)) }
        assertEquals(a,r.selected)
    }
    @Test fun `no new trial after absolute deadline`() = runBlocking {
        val a=chain("a");val b=chain("b");var clock=1000L;var opens=0
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{clock},{1}) {
            opens++;object:Session { override suspend fun measure(remainingMs:Long):Sample { clock=16_000;return sample(it,at=clock,tg=Verdict.UNKNOWN) }; override fun close(){} }
        }
        assertEquals(1,opens);assertEquals(State.FALLBACK,r.state);assertEquals(1,r.fallbackCount)
    }
    @Test fun `late result cannot be selected`() = runBlocking {
        var clock=1000L
        val r=A.select(listOf(chain()),caps,Purpose.DOWNLOAD,{clock},{1}) {
            object:Session { override suspend fun measure(remainingMs:Long):Sample {clock=16_001;return sample(it,at=clock)};override fun close(){} }
        }
        assertEquals(State.NO_ROUTE,r.state);assertNull(r.selected)
    }
    @Test fun `network change discards even previous successful trials`() = runBlocking {
        var epoch=1L;val a=chain("a");val b=chain("b");var closed=0
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{epoch}) {
            if(it==b)epoch=2
            session(sample(it),{closed++})
        }
        assertEquals(State.NETWORK_CHANGED,r.state);assertNull(r.selected);assertEquals(2,closed)
    }
    @Test fun `parent cancellation closes session and never returns route`() = runBlocking {
        var closed=false;val started=CompletableDeferred<Unit>()
        val task=async {
            A.select(listOf(chain()),caps,Purpose.DOWNLOAD,{1000},{1}) {
                object:Session { override suspend fun measure(remainingMs:Long):Sample {started.complete(Unit);awaitCancellation()};override fun close(){closed=true} }
            }
        }
        started.await();task.cancelAndJoin();assertTrue(task.isCancelled);assertTrue(closed)
    }
    @Test fun `cleanup failure stops search without new session`() = runBlocking {
        var opens=0
        val r=A.select(listOf(chain("a"),chain("b")),caps,Purpose.DOWNLOAD,{1000},{1}) {
            opens++;session(sample(it),{error("fixture cleanup failure")})
        }
        assertEquals(1,opens);assertEquals(State.NO_ROUTE,r.state);assertTrue("cleanup-error" in r.diagnostics)
    }
    @Test fun `ordinary probe failure cleans up and tries next candidate`() = runBlocking {
        var closed=0;val b=chain("b")
        val r=A.select(listOf(chain("a"),b),caps,Purpose.DOWNLOAD,{1000},{1}) {
            if(it==b)session(sample(it)) else object:Session {override suspend fun measure(remainingMs:Long):Sample=error("fixture");override fun close(){closed++}}
        }
        assertEquals(b,r.selected);assertEquals(1,closed)
    }
    @Test fun `all service failures are no route not fallback`() = runBlocking {
        val r=A.select(listOf(chain()),caps,Purpose.DOWNLOAD,{1000},{1}) {session(sample(it,tg=Verdict.FAIL))}
        assertEquals(State.NO_ROUTE,r.state);assertEquals(0,r.fallbackCount)
    }
    @Test fun `all incompatible candidates start no work`() = runBlocking {
        var opens=0
        val r=A.select(listOf(chain(layout=Layout.TOR)),caps.copy(torOptIn=false),Purpose.DOWNLOAD,{1000},{1}) {opens++;session(sample(it))}
        assertEquals(0,opens);assertEquals(State.NO_ROUTE,r.state)
    }
    @Test fun `monotonic rollback fails closed`() = runBlocking {
        var now=1000L
        try { A.select(listOf(chain()),caps,Purpose.DOWNLOAD,{now},{1}) {
            now=999;session(sample(it))
        };fail() }catch (_:IllegalStateException){}
    }
    @Test fun `recent cached sample is not a new chain measurement`() = runBlocking {
        val r=A.select(listOf(chain()),caps,Purpose.DOWNLOAD,{1000},{1}) { session(sample(it,at=999)) }
        assertEquals(State.NO_ROUTE,r.state)
    }
    @Test fun `planner cannot scan whole catalog for compatible route`() {
        val candidates=(1..32).map {chain("s$it").copy(serverExitAllowed=false)}+chain("afterBound")
        assertTrue(A.plan(candidates,caps).isEmpty())
    }
    @Test fun `probe timeout closes session and preserves earlier confirmed result`() = runBlocking {
        val a=chain("a");val b=chain("b");var closed=false
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{1}) {
            if(it==a)session(sample(it)) else object:Session {
                override suspend fun measure(remainingMs:Long):Sample=awaitCancellation()
                override fun close(){closed=true}
            }
        }
        assertEquals(a,r.selected);assertTrue(closed);assertEquals(State.CONFIRMED,r.state)
    }
    @Test fun `startup failure is not success and next adapter may proceed`() = runBlocking {
        val a=chain("a");val b=chain("b")
        val r=A.select(listOf(a,b),caps,Purpose.DOWNLOAD,{1000},{1}) {
            if(it==a)error("fixture startup failed before resource acquisition") else session(sample(it))
        }
        assertEquals(b,r.selected);assertEquals(2,r.attempted)
    }

}
