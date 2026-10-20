package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AutoServiceBudgetRegressionTest {
    @Test fun workingSlowMtprotoHandshakeIsNotCutOffAtOld1500ms() {
        var clock = 0L
        val result = TelegramProbe.evaluate(8000, { clock }) { _, allowance ->
            assertEquals(4000L,allowance)
            clock += 2300
            TelegramProbe.Result(true,"mtproto-response")
        }
        assertEquals(true,result.ok); assertEquals(2300L,clock)
    }
    @Test fun twoDcAttemptsShareBoundedBudgetAndTimeoutIsUnknown() {
        var clock = 0L; var attempts = 0
        val result = TelegramProbe.evaluate(8000,{ clock }) { _, allowance ->
            attempts++; clock += allowance; TelegramProbe.Result(null,"mtproto-timeout")
        }
        assertNull(result.ok); assertEquals(2,attempts); assertEquals(8000L,clock)
    }
    @Test fun firstInconclusiveDcIsNotOverriddenBySecondConnectFailure() {
        var n=0
        val result=TelegramProbe.evaluate(8000,{0L}) { _,_ ->
            if (++n == 1) TelegramProbe.Result(null,"mtproto-timeout") else TelegramProbe.Result(false,"mtproto-connect")
        }
        assertNull(result.ok)
    }
    @Test fun noBudgetDoesNotStartNetworkWork() {
        assertNull(TelegramProbe.evaluate(0,{0L}) { _,_ -> error("network work") }.ok)
    }
    @Test fun cancellationStopsBeforeSecondDc() {
        var n=0
        assertNull(TelegramProbe.evaluate(8000,{0L}) { _,_ -> n++; TelegramProbe.Result(null,"cancelled") }.ok)
        assertEquals(1,n)
    }
    @Test fun slowMediaAndDownloadCannotStarveTelegram() {
        ProbeWorkerSet(1,1).use { workers ->
            val release=CountDownLatch(1); val mediaStarted=CountDownLatch(1); val downloadStarted=CountDownLatch(1)
            try {
                workers.media(false) { mediaStarted.countDown(); release.await(); true }
                workers.download(false) { downloadStarted.countDown(); release.await(); true }
                assertTrue(mediaStarted.await(1,TimeUnit.SECONDS)); assertTrue(downloadStarted.await(1,TimeUnit.SECONDS))
                assertEquals(true,workers.telegram(false) { true }.get(1,TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }
    @Test fun cancellingWholeProbeClosesTelegramAndBothHttpSockets() = kotlinx.coroutines.runBlocking {
        val listener=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val connected=CountDownLatch(3); val closed=CountDownLatch(3)
        val peers=java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val acceptor=kotlin.concurrent.thread(isDaemon=true) {
            while (!listener.isClosed) {
                val socket=runCatching { listener.accept() }.getOrNull() ?: break
                peers += socket
                kotlin.concurrent.thread(isDaemon=true) {
                    runCatching { socket.use { peer ->
                        peer.soTimeout=4000
                        val input=java.io.DataInputStream(peer.getInputStream()); val out=peer.getOutputStream()
                        check(input.readUnsignedByte()==5)
                        input.readFully(ByteArray(input.readUnsignedByte()))
                        out.write(byteArrayOf(5,0));out.flush()
                        check(input.readUnsignedByte()==5);input.readUnsignedByte();input.readUnsignedByte()
                        when(input.readUnsignedByte()) {
                            1 -> input.readFully(ByteArray(4))
                            3 -> input.readFully(ByteArray(input.readUnsignedByte()))
                            4 -> input.readFully(ByteArray(16))
                            else -> error("address")
                        }
                        input.readUnsignedShort()
                        out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
                        connected.countDown()
                        while(input.read() >= 0) { }
                        closed.countDown()
                    } }
                }
            }
        }
        val task=launch {
            CancellableProbe.run { bind -> Tester.probeSocks(listener.localPort,"http://probe.invalid/",big=false,
                youtube=true,attempts=1,budgetMs=8000,onCall={ call -> bind { call.cancel() } }) }
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue(connected.await(3,TimeUnit.SECONDS)) }
            task.cancel(); task.join()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assertTrue("probe sockets leaked",closed.await(2,TimeUnit.SECONDS)) }
        } finally {
            task.cancel(); task.join(); listener.close(); peers.forEach { runCatching { it.close() } }; acceptor.join(1000)
        }
    }
    @Test fun saturatedQueueReturnsExplicitUnknownInsteadOfBlocking() {
        ProbeWorkerSet(1,1).use { workers ->
            val started=CountDownLatch(1); val release=CountDownLatch(1)
            try {
                workers.telegram("busy") { started.countDown(); release.await(); "done" }
                assertTrue(started.await(1,TimeUnit.SECONDS))
                workers.telegram("busy") { "queued" }
                assertEquals("busy",workers.telegram("busy") { error("must not execute") }.get(1,TimeUnit.SECONDS))
            } finally { release.countDown() }
        }
    }
    @Test fun loginChallengeIsResourceUnknownNotMediaPassOrRouteFailure() {
        val d=YouTubeMediaProbe.playerResource("ytInitialPlayerResponse = {\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\"}};")
        assertNull(d.url); assertEquals("player-login-required",d.reason)
        val probe=Probe(25,null,null,tgOk=true,ytReason=d.reason)
        assertNull(AutoEligibility.learningVerdict(probe,true))
        assertFalse(AutoEligibility.servicesWork(probe))
    }
    @Test fun encryptedOrMissingMediaUrlDoesNotBecomeSuccessfulProbe() {
        val d=YouTubeMediaProbe.playerResource("ytInitialPlayerResponse = {\"playabilityStatus\":{\"status\":\"OK\"},\"streamingData\":{\"formats\":[{\"signatureCipher\":\"not-a-direct-url\",\"mimeType\":\"video/mp4\"}]}};")
        assertNull(d.url); assertEquals("player-no-direct-media",d.reason)
        assertEquals("player-incomplete",YouTubeMediaProbe.playerResource("ytInitialPlayerResponse = {").reason)
    }
    @Test fun onlyIsolatedMediaSpeedCanEnterRanking() {
        val p=Probe(15,null,true,tgOk=true,ytKbps=8000)
        assertEquals(0,ServiceProbePolicy.isolatedSpeed(p,2))
        assertEquals(8000,ServiceProbePolicy.isolatedSpeed(p,1))
        assertEquals(2000,ServiceProbePolicy.isolatedSpeed(p.copy(ytOk=null,kbps=2000),1))
    }
    @Test fun reportDoesNotConflateDifferentRouteServicePassesOrExposeRouteIdentifiers() {
        val r=AutoSearchReport()
        r.record("secret-private-url-A",Probe(10,null,false,tgOk=true,ytReason="early-eof"))
        r.record("secret-private-url-B",Probe(10,null,true,tgOk=false))
        val text=r.summary()
        assertTrue(text.contains("MTProto: 1")); assertTrue(text.contains("YouTube-медиа: 1"))
        assertTrue(text.contains("не подтверждение общей цепочки")); assertFalse(text.contains("secret"))
        assertFalse(r.resourceUnavailable())
    }
    @Test fun repeatedResourceChallengeStopsWasteButOneDoesNotBlacklistCatalog() {
        val r=AutoSearchReport(); val p=Probe(10,null,null,tgOk=true,ytReason="player-login-required")
        r.record("a",p);assertFalse(r.resourceUnavailable())
        r.record("b",p);assertTrue(r.resourceUnavailable())
        assertNull(AutoEligibility.learningVerdict(p,true))
        assertFalse(AutoEligibility.servicesWork(p))
    }
    @Test fun confirmedServiceFailureStillFailsStrictAdmissionAndLearning() {
        val p=Probe(10,null,true,tgOk=false,tgReason="mtproto-invalid")
        assertEquals(false,AutoEligibility.learningVerdict(p,true)); assertFalse(AutoEligibility.servicesWork(p))
        assertTrue(ServiceProbePolicy.describe(p).contains("MTProto"))
    }
    @Test fun queuedHandoffCannotConnectAfterTimeoutOrCancellation() {
        val cancelled=AutoSearchHandoff(); assertTrue(cancelled.expire()); assertFalse(cancelled.claim())
        val connected=AutoSearchHandoff(); assertTrue(connected.claim()); assertFalse(connected.expire()); assertFalse(connected.claim())
    }
    @Test fun immediateProofReuseRequiresFreshSamePhysicalNetworkAndFullServicePass() {
        val proof=RecentServiceProof(Probe(20,null,true,tgOk=true),"wifi-A","handle1",1000)
        assertTrue(proof.matches("wifi-A","handle1",1001))
        assertFalse(proof.matches("wifi-A","handle2",1001))
        assertFalse(proof.matches("wifi-B","handle1",1001))
        assertFalse(proof.matches("wifi-A","handle1",31001))
        assertFalse(proof.matches("wifi-A","handle1",999))
        assertFalse(proof.copy(probe=proof.probe.copy(tgOk=null)).matches("wifi-A","handle1",1001))
    }
    @Test fun reportIsBoundedAndTransient() {
        val r=AutoSearchReport(); repeat(100) { r.record("$it",Probe(1,null,null)) }
        assertTrue(r.summary().contains("маршрутов: 64"))
        assertTrue(AutoSearchReport().summary().contains("маршрутов: 0"))
    }
}
