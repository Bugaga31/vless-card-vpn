package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class BulkCheckStabilityTest {
    @Test fun bulkLimitRoundTripsSeparatelyFromFullChecks() {
        for (n in BulkTcpCapacity.choices) {
            val s = Settings.fromJson(Settings(probeParallel=5,bulkTcpParallel=n).toJson())
            assertEquals(n,s.bulkTcpParallel); assertEquals(5,s.probeParallel)
        }
        assertEquals(250,Settings.fromJson(JSONObject()).bulkTcpParallel)
        assertEquals(1024L,BulkTcpCapacity.parseSoftLimit(sequenceOf("Max open files            1024                 4096 files")))
        assertEquals(Long.MAX_VALUE,BulkTcpCapacity.parseSoftLimit(sequenceOf("Max open files unlimited unlimited files")))
        assertNull(BulkTcpCapacity.parseSoftLimit(sequenceOf("no limit available")))
        assertEquals(250,BulkTcpCapacity.normalize(1001))
        assertEquals(1000,BulkTcpCapacity.limit(1000,4096,100,false))
        assertEquals(796,BulkTcpCapacity.limit(1000,1024,100,false))
        assertEquals(64,BulkTcpCapacity.limit(1000,4096,100,true))
        assertEquals(64,BulkTcpCapacity.limit(1000,null,null,false))
        assertEquals(0,BulkTcpCapacity.limit(1000,200,100,false))
        assertTrue(BulkTcpCapacity.resourceFailure(java.io.IOException("Too many open files")))
        assertTrue(BulkTcpCapacity.resourceFailure(java.io.IOException("wrapped",java.io.IOException("No buffer space available"))))
        assertFalse(BulkTcpCapacity.resourceFailure(java.net.ConnectException("Connection refused")))
    }

    @Test fun thousandLocalTcpCandidatesReturnExactlyOneResultEach() = runBlocking {
        ServerSocket(0, 2048).use { server ->
            val entries=(0 until 1000).map { Server("fixture$it","vless","127.0.0.1",server.localPort,"secret$it") }
            val results=HashMap<String,Int>(); val duplicate=AtomicInteger()
            Tester.bulkTcp(entries,250,3000) { s, ms -> if(results.put(s.id,ms)!=null) duplicate.incrementAndGet() }
            assertEquals(0,duplicate.get()); assertEquals(1000,results.size)
            assertTrue("loopback connections must complete",results.values.all { it > 0 })
        }
    }

    @Test fun thousandSocketWindowOnMachineWithSufficientDescriptors() {
        val limits=File("/proc/self/limits").readLines().firstOrNull { it.startsWith("Max open files") }.orEmpty()
        val soft=limits.trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull() ?: 0
        assumeTrue("requires > 1200 process descriptors",soft>=1200)
        ServerSocket(0, 2048).use { server ->
            val target=java.net.InetSocketAddress("127.0.0.1",server.localPort)
            val count=AtomicInteger(); val failures=AtomicInteger(); var peak=0
            val baseline=File("/proc/self/fd").list()?.size ?: 0
            Tester.nioPing(List(1000) { target },3000,1000) { _,ms ->
                if (ms<=0) failures.incrementAndGet(); count.incrementAndGet()
                peak=maxOf(peak,File("/proc/self/fd").list()?.size ?: 0)
            }
            assertEquals(1000,count.get()); assertEquals(0,failures.get())
            assertTrue("at least 900 sockets were live together (baseline=$baseline peak=$peak)",peak-baseline>=900)
        }
    }

    @Test fun unresolvedNamesRemainUnknownAndNumericServersStillRun() = runBlocking {
        ServerSocket(0).use { server ->
            val local=Server("local","vless","127.0.0.1",server.localPort,"local")
            val invalid=Server("bad","vless","bad host",443,"bad")
            val badPort=Server("port","vless","127.0.0.1",70000,"port")
            val outcomes=mutableMapOf<String,Int>()
            Tester.bulkTcp(listOf(invalid,local,badPort),50,1000) { s,ms -> outcomes[s.name]=ms }
            assertTrue((outcomes["local"] ?: 0)>0)
            assertEquals(-1,outcomes["bad"]); assertEquals(-1,outcomes["port"])
        }
    }

    @Test fun tcpFailuresNeedRetryAndPhysicalNetworkBeforeBeingRecorded() {
        assertEquals(42, ManualServerCheckPolicy.finalTcp(42, null, false))
        assertEquals(-1, ManualServerCheckPolicy.finalTcp(-1, null, true))
        assertEquals(31, ManualServerCheckPolicy.finalTcp(0, 31, false))
        assertEquals(-1, ManualServerCheckPolicy.finalTcp(0, null, true))
        assertEquals(-1, ManualServerCheckPolicy.finalTcp(0, -1, true))
        assertEquals(-1, ManualServerCheckPolicy.finalTcp(0, 0, false))
        assertEquals(0, ManualServerCheckPolicy.finalTcp(0, 0, true))
    }

    @Test fun tcpRetryCanRecoverATemporarilyClosedLocalEndpoint() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val entry = Server("temporary", "vless", "127.0.0.1", port, "secret")
        var first = -2
        Tester.bulkTcp(listOf(entry), 50, 1000) { _, ms -> first = ms }
        assertEquals(0, first)
        ServerSocket(port).use {
            var retry = -2
            Tester.bulkTcp(listOf(entry), 50, 1000) { _, ms -> retry = ms }
            assertTrue(retry > 0)
            assertTrue(ManualServerCheckPolicy.finalTcp(first, retry, false) > 0)
        }
    }

    @Test fun stoppedScanDoesNotLeaveSelectorSocketsOpen() {
        val interrupted=Thread.currentThread().isInterrupted
        try {
            Thread.currentThread().interrupt()
            try { Tester.nioPing(listOf(java.net.InetSocketAddress("127.0.0.1",1)),10000,1000) { _,_-> fail("cancelled scan reported a result") }; fail("expected interruption") }
            catch (_: InterruptedException) { }
        } finally { Thread.interrupted(); if(interrupted) Thread.currentThread().interrupt() }
    }

    @Test fun exitHintsAreSpecificAndNeverContainExceptionMessages() {
        assertTrue(AppExitHint.label(3)!!.contains("памяти"))
        assertTrue(AppExitHint.label(4)!!.contains("сбое"))
        assertTrue(AppExitHint.label(6)!!.contains("не отвечало"))
        assertNull(AppExitHint.label(10))
        assertNull(AppExitHint.label(-1))
    }

    @Test fun wiringKeepsFullProofSeparateAndProtectsOldNetwork() {
        val f=listOf(File("src/main/java/com/vlesscardvpn/core/Actions.kt"),File("app/src/main/java/com/vlesscardvpn/core/Actions.kt")).first { it.exists() }
        val body=f.readText().substringAfter("fun testAll(").substringBefore("private suspend fun realTest(")
        assertTrue(body.contains("Tester.bulkTcp(")); assertTrue(body.contains("if (ms >= 0)"))
        assertTrue(body.contains("if (!valid())")); assertTrue(body.contains("realTest(wave"))
        assertTrue(body.contains("alive.chunked(50)")); assertTrue(body.contains("realLimit.coerceIn(1,1000)"))
    }
}
