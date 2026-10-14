package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class ThroughputSelectionTest {
    private fun measured(ms:Int=150,kbps:Int=20000) = ServerState(realMs=ms,bigOk=true,kbps=kbps,speedNetwork="wifi",speedMask="",speedCheckedAt=1000)
    @Test fun lowPingSlowDownloadDoesNotBeatFastDownload() {
        assertTrue(ServerChoice.qualityScore(measured(250,20000),"wifi",2000) < ServerChoice.qualityScore(measured(20,500),"wifi",2000))
    }
    @Test fun aOneMegabitRouteNoLongerLooksLikeTwoMegabitsWithMoreLatency() {
        assertTrue(ServerChoice.qualityScore(measured(1000,2000),"wifi",2000)<ServerChoice.qualityScore(measured(20,1000),"wifi",2000))
    }
    @Test fun unknownSpeedIsNotArtificiallyFast() {
        assertTrue(ServerState.speedPenalty(0)>ServerState.speedPenalty(5000))
        assertTrue(ServerChoice.qualityScore(measured(),"wifi",2000)<ServerChoice.qualityScore(ServerState(realMs=20),"wifi",2000))
    }
    @Test fun foreignNetworkSpeedCannotBeALiveRating() { assertEquals(0,ServerChoice.measuredSpeed(measured(),"cell",2000)) }
    @Test fun changedMaskInvalidatesSpeedSample() { assertEquals(0,ServerChoice.measuredSpeed(measured().copy(maskId="another"),"wifi",2000)) }
    @Test fun staleAndFutureSpeedsAreNotCurrentEvidence() {
        assertEquals(0,ServerChoice.measuredSpeed(measured(),"wifi",1000+31*60000L))
        assertEquals(0,ServerChoice.measuredSpeed(measured(),"wifi",999))
    }
    @Test fun legacySpeedIsOnlyAPriorForRechecking() {
        val old=ServerState(realMs=200,kbps=40000,bigOk=true)
        assertEquals(0,ServerChoice.measuredSpeed(old,"wifi",2000))
        assertTrue(ServerChoice.candidateScore(old,"wifi",2000)<ServerChoice.qualityScore(old,"wifi",2000))
    }
    @Test fun speedTimestampDoesNotFollowConnectivityTimestamp() { assertEquals(0,ServerChoice.measuredSpeed(measured().copy(speedCheckedAt=1000,checkedAt=3000000),"wifi",3000000)) }
    @Test fun speedProvenanceRoundTrips() { assertEquals(measured(),ServerState.fromJson(measured().toJson())) }
    @Test fun deadOrFailedBigTransferStillCannotWin() {
        assertEquals(Int.MAX_VALUE,ServerChoice.qualityScore(measured().copy(realMs=0),"wifi",2000))
        assertEquals(Int.MAX_VALUE,ServerChoice.qualityScore(measured().copy(bigOk=false),"wifi",2000))
    }
    @Test fun rememberedSlowRouteCannotOverrideBetterCandidateRank() {
        val slow=Server("slow","vless","slow.invalid",443,"id1");val fast=Server("fast","vless","fast.invalid",443,"id2")
        assertEquals(fast,ConnectPolicy.candidates(listOf(slow,fast),listOf(slow),setOf(slow.id)) { if(it==fast)0 else 100 }.first())
    }
    @Test fun automaticPrimaryDoesNotAlterExplicitOtherBalancerChoice() {
        assertTrue(ServerChoice.automaticPrimary(true,true,false));assertFalse(ServerChoice.automaticPrimary(false,true,false))
        assertFalse(ServerChoice.automaticPrimary(true,false,false));assertTrue(ServerChoice.automaticPrimary(false,false,true))
    }
    @Test fun fourTimesSlowerReserveIsNotForcedIntoTheLivePool() { assertEquals(1,Actions.closeOnes(listOf(200,800,1300)));assertEquals(2,Actions.closeOnes(listOf(200,260,700))) }
    @Test fun totalBudgetCoversPingThenDownloadNotEachRequestSeparately() {
        val sockets=CopyOnWriteArrayList<Socket>();val server=ServerSocket(0)
        val accept=Thread {
            while(!server.isClosed) {
                val s=try { server.accept() } catch(_:Exception){break};sockets.add(s)
                Thread {
                    try {
                        s.soTimeout=5000;val inp=s.getInputStream();val out=s.getOutputStream()
                        check(inp.read()==5);val count=inp.read();inp.readNBytes(count);out.write(byteArrayOf(5,0));out.flush()
                        check(inp.read()==5);inp.read();inp.read();val type=inp.read()
                        when(type){1->inp.readNBytes(4);3->inp.readNBytes(inp.read());4->inp.readNBytes(16);else->error("address")};inp.readNBytes(2)
                        out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80));out.flush()
                        val first=inp.read()
                        if(first=='G'.code) {
                            val text=StringBuilder("G");while(!text.endsWith("\r\n\r\n") && text.length<8192){val n=inp.read();if(n<0)break;text.append(n.toChar())}
                            Thread.sleep(800);out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
                        } else Thread.sleep(5000) // TLS download stalls; must share the remaining budget.
                    } catch(_:Exception) {} finally { runCatching { s.close() } }
                }.apply { isDaemon=true }.start()
            }
        }.apply { isDaemon=true };accept.start()
        try {
            assertTrue(Tester.probeSocks(server.localPort,"http://test.invalid/",big=false,youtube=false,attempts=1).realMs>0)
            val start=System.nanoTime();val p=Tester.probeSocks(server.localPort,"http://test.invalid/",big=true,youtube=false,attempts=1,budgetMs=1300)
            val elapsed=(System.nanoTime()-start)/1000000
            assertTrue("ping was actually served: $p",p.realMs>=700)
            assertTrue("global budget exceeded: $elapsed",elapsed<1800)
        } finally { server.close();sockets.forEach { runCatching { it.close() } };accept.join(1000) }
    }
}
