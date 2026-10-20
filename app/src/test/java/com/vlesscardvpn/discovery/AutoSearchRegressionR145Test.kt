package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutoSearchRegressionR145Test {
    private fun server(n: Int) = Server("route-$n","vless","host$n.invalid",443,"test-$n")
    private val now=1000000L
    private fun proven(speed: Int=8000) = ServerState(realMs=40,bigOk=true,maskId="chrome.h4",selected=true,
        serviceNetwork="wifi",serviceMask="chrome.h4",serviceCheckedAt=now,serviceTgOk=true,serviceYtOk=true,
        kbps=speed,speedNetwork="wifi",speedMask="chrome.h4",speedCheckedAt=if(speed>0)now else 0)

    @Test fun liveCandidateBeyondFormerFirst24GetsCheapReachabilitySlot() {
        val catalog=(0..299).map(::server)
        val wave=ForegroundDiscovery().window(catalog,"wifi")
        assertEquals(128,wave.size);assertTrue(catalog[120] in wave)
        assertFalse(catalog.take(24).contains(catalog[120]))
    }
    @Test fun repeatedSearchRotatesRatherThanRecheckingSameFirstDeadWindow() {
        val catalog=(0..299).map(::server);val search=ForegroundDiscovery()
        val first=search.window(catalog,"wifi");val second=search.window(catalog,"wifi")
        assertTrue(first.toSet().intersect(second.toSet()).isEmpty())
        assertTrue(catalog[200] in second)
        assertEquals(first,search.window(catalog,"cell"))
    }
    @Test fun emptySmallAndDuplicateCatalogsNeverInventCandidates() {
        val s=ForegroundDiscovery();assertTrue(s.window(emptyList(),"wifi").isEmpty())
        val a=server(1);assertEquals(listOf(a),s.window(listOf(a,a),"wifi"))
        assertEquals(listOf(a),s.window(listOf(a),"wifi"))
    }
    @Test fun nextServiceWaveUsesAlreadyHttpsTestedCandidatesBeforeUntestedCatalog() {
        val verified=(0..7).map(::server);val extra=(8..15).map(::server)
        val first=ForegroundDiscovery.serviceWave(verified+extra,emptySet())
        val second=ForegroundDiscovery.serviceWave(verified+extra,first.map {it.id}.toSet())
        assertEquals(verified.take(2),first);assertEquals(verified.drop(2).take(2),second)
    }
    @Test fun failedActualChainRevokesCachedAdmissionWithoutInventingServerFailure() {
        val before=proven();val after=AutoEligibility.invalidateChainProof(before,"wifi","chrome.h4",now+1)
        assertTrue(AutoEligibility.servicePool(listOf(before),{it},"wifi",now+2).isNotEmpty())
        assertTrue(AutoEligibility.servicePool(listOf(after),{it},"wifi",now+2).isEmpty())
        assertFalse(after.selected);assertEquals(0L,after.speedCheckedAt)
        assertEquals(before.kbps,after.kbps);assertEquals(before.failCount,after.failCount)
        assertEquals(before.netEvidence,after.netEvidence)
        assertTrue(after.works) // manual entry and history are retained; not a catalog-wide ban
    }
    @Test fun lateFailedChainCannotEraseNewerProofOrOtherNetworkOrMask() {
        val s=proven()
        assertEquals(s,AutoEligibility.invalidateChainProof(s,"cell","chrome.h4",now+1))
        assertEquals(s,AutoEligibility.invalidateChainProof(s,"wifi","chrome.h6",now+1))
        assertEquals(s,AutoEligibility.invalidateChainProof(s,"wifi","chrome.h4",now-1))
        val faster=s.copy(speedCheckedAt=now+3)
        assertEquals(now+3,AutoEligibility.invalidateChainProof(faster,"wifi","chrome.h4",now+1).speedCheckedAt)
    }
    @Test fun unmeasuredLowPingCannotBecomeAutomaticWinnerAfterInconclusiveDownload() = runBlocking {
        val a=server(1);val state=proven(0).copy(realMs=1)
        val selected=AutoSelection.compare(listOf(a),{state},"wifi",{now},{0L},{true},{true},false){_,_->}
        assertNull(selected);assertTrue(state.works);assertEquals(0,ServerChoice.measuredSpeed(state,"wifi",now))
    }
    @Test fun lowPowerDoesNotTurnMissingSpeedIntoFastestClaim() = runBlocking {
        val a=server(1);val state=proven(0)
        assertNull(AutoSelection.compare(listOf(a),{state},"wifi",{now},{0L},{true},{false},false){_,_->error("power budget")})
    }
    @Test fun slowerMeasuredRouteBeatsUnmeasuredLowPingWithoutErasingHistory() = runBlocking {
        val a=server(1);val b=server(2)
        val states=mapOf(a to proven(0).copy(realMs=1),b to proven(9000).copy(realMs=140))
        assertEquals(b,AutoSelection.compare(listOf(a,b),{states.getValue(it)},"wifi",{now},{0L},{true},{true},false){_,_->})
    }
    @Test fun definiteTelegramFailureDoesNotWaitForStalledMediaDeadline() {
        val listener=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val mediaClosed=CountDownLatch(1);val peers=java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val acceptor=kotlin.concurrent.thread(isDaemon=true) {
            while (!listener.isClosed) {
                val socket=runCatching {listener.accept()}.getOrNull() ?: break;peers+=socket
                kotlin.concurrent.thread(isDaemon=true) {
                    runCatching { socket.use { peer ->
                        peer.soTimeout=4000
                        val input=java.io.DataInputStream(peer.getInputStream());val out=peer.getOutputStream()
                        check(input.readUnsignedByte()==5);input.readFully(ByteArray(input.readUnsignedByte()))
                        out.write(byteArrayOf(5,0));out.flush()
                        check(input.readUnsignedByte()==5);input.readUnsignedByte();input.readUnsignedByte()
                        val host=when(input.readUnsignedByte()) {
                            1 -> ByteArray(4).also(input::readFully).joinToString("."){(it.toInt() and 255).toString()}
                            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                            else -> error("address")
                        }
                        input.readUnsignedShort();out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
                        when {
                            host=="probe.invalid" -> { while(!input.readLine().isNullOrEmpty()){};out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray());out.flush() }
                            host.startsWith("149.154.") -> {input.readFully(ByteArray(42));out.write(1);out.flush()}
                            else -> {while(input.read()>=0){};mediaClosed.countDown()}
                        }
                    } }
                }
            }
        }
        try {
            val start=System.nanoTime()
            val p=Tester.probeSocks(listener.localPort,"http://probe.invalid/",big=false,youtube=true,attempts=1,budgetMs=8000,strictServices=true)
            assertTrue(p.works);assertEquals(false,p.tgOk);assertFalse(AutoEligibility.servicesWork(p))
            assertTrue("waited for media despite definite Telegram failure",(System.nanoTime()-start)/1000000<2500)
            assertTrue(mediaClosed.await(2,TimeUnit.SECONDS))
        } finally {listener.close();peers.forEach {runCatching {it.close()}};acceptor.join(1000)}
    }
}
