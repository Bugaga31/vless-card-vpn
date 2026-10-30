package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

import com.vlesscardvpn.watson.*
import com.vlesscardvpn.ui.WatsonCatSprite

class WatsonAutoR217Test {
    @Test fun queuedTelegramWorkerDoesNotBecomeServerLatency() {
        val error = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3000
            val worker = thread(isDaemon = true, name = "test-tg-socks") {
                try { listener.accept().use { s ->
                    s.soTimeout = 3000
                    val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                    assertEquals(5, input.readUnsignedByte())
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    out.write(byteArrayOf(5, 0)); out.flush()
                    assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                    input.readUnsignedByte()
                    val address = when (val type = input.readUnsignedByte()) {
                        1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 255).toString() }
                        3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                        else -> error("unexpected address type $type")
                    }
                    assertTrue(address in setOf("149.154.167.51","149.154.175.50")); assertEquals(443, input.readUnsignedShort())
                    out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1)); out.flush()
                    val req = ByteArray(42); input.readFully(req)
                    assertEquals(239, req[0].toInt() and 255); assertEquals(10, req[1].toInt())
                    val nonce = req.copyOfRange(26, 42)
                    val response = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(nonce).put(ByteArray(16) { 1 })
                        .put(8).put(ByteArray(8).apply { this[7]=15 }).put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(1).array()
                    out.write(21); out.write(response); out.flush()
                } } catch (e: Throwable) { error.set(e) }
            }
            val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
            val entered=java.util.concurrent.CountDownLatch(1)
            executor.execute {entered.countDown();Thread.sleep(650)}
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            val started=System.nanoTime()
            val result=try {TelegramProbe.measureOn(listener.localPort,3000,null,executor)} finally {executor.shutdownNow()}
            val total=(System.nanoTime()-started)/1000000
            assertEquals(true,result.ok)
            assertTrue("queue was counted as server latency: total=$total route=${result.ms}",total-result.ms>=400)
            worker.join(3500)
            error.get()?.let { throw AssertionError("fake SOCKS failed", it) }
            assertFalse(worker.isAlive)
        }
    }

    @Test fun pixelCatHasConsistentGeometryAndVisibleEars() {
        val rows=WatsonCatSprite.rows
        assertEquals(16,rows.size);assertTrue(rows.all{it.length==16})
        assertTrue(rows.all{it.all{c->c in ".KFLPEW"}})
        assertEquals("..KK........KK..",rows.first())
    }
    @Test fun flyPhoneStartsUntrainedAndUsesFutureLabelsOnlyWhenCalled() {
        val m=FlyPhoneLearning();val x=doubleArrayOf(.5,.2,-1.0,1.0,.8)
        assertFalse(m.ready());assertEquals(0,m.samples)
        repeat(40){m.train(x,it%2==0)}
        assertTrue(m.ready());assertEquals(40,m.samples)
        assertTrue(m.predict(x).isFinite())
        val saved=m.encode();val restored=FlyPhoneLearning();restored.restore(saved)
        assertEquals(saved,restored.encode());assertEquals(m.predict(x),restored.predict(x),1e-12)
    }
    @Test fun invalidPhoneInputDoesNotTrainAndMalformedWeightsAreRejected() {
        val m=FlyPhoneLearning();m.train(doubleArrayOf(Double.NaN,0.0,0.0,0.0,0.0),true)
        assertEquals(0,m.samples);assertEquals(.5,m.predict(doubleArrayOf()),0.0)
        assertTrue(runCatching{m.restore("1,2,3")}.isFailure)
        assertEquals(0,m.samples)
    }
    @Test fun singleClassPhoneReadoutNeverClaimsReadiness() {
        val m=FlyPhoneLearning();repeat(50){m.train(DoubleArray(5),false)}
        assertFalse(m.ready())
    }
    @Test fun unsupportedUrlRemainsUnknownToBothModels() {
        val r=WatsonUrl.check("javascript:alert(1)")
        assertFalse(r.measured);assertFalse(r.flyMeasured);assertFalse(r.flyWarning)
        assertNull(WatsonFlyUrl.score(DoubleArray(2)))
    }
    @Test fun flyUrlPythonParity0() {
        val score=WatsonFlyUrl.score(doubleArrayOf(3.4965075614664802,3.2188758248682006,0.0,0.0,2.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,3.6572682296173893,16.0,0.0,0.0))
        assertNotNull(score);assertEquals(0.027703051566299243,score!!,1e-10)
    }
    @Test fun flyUrlPythonParity1() {
        val score=WatsonFlyUrl.score(doubleArrayOf(4.02535169073515,3.8501476017100584,0.6931471805599453,0.0,3.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,1.0,1.0,0.0,0.0,0.0,0.0,4.129236619374021,20.0,0.0,0.0))
        assertNotNull(score);assertEquals(0.9998478065239051,score!!,1e-10)
    }
    @Test fun flyUrlPythonParity2() {
        val score=WatsonFlyUrl.score(doubleArrayOf(3.4657359027997265,3.2188758248682006,0.0,0.0,3.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,3.4072682296173893,10.0,0.0,0.0))
        assertNotNull(score);assertEquals(0.9779374020901603,score!!,1e-10)
    }
}
