package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Only a local SOCKS fixture: no Telegram network or user data. */
class SameEndpointSocksR252Test {
    @Test fun raceWinnerIsReusedThroughSocksForBothFollowups() {
        val listener=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
        val firstSeen=CountDownLatch(1)
        val slow=AtomicInteger();val fast=AtomicInteger()
        val jobs=CopyOnWriteArrayList<Thread>()
        val stop=AtomicBoolean(false);val error=AtomicReference<Throwable?>()
        val server=thread(isDaemon=true) {
            while(!stop.get()) try {
                val socket=listener.accept()
                jobs+=thread(isDaemon=true) {
                    try {socket.use { s->
                        s.soTimeout=3000
                        val input=DataInputStream(s.getInputStream());val out=s.getOutputStream()
                        check(input.readUnsignedByte()==5);val methods=ByteArray(input.readUnsignedByte());input.readFully(methods)
                        out.write(byteArrayOf(5,0));out.flush()
                        check(input.readUnsignedByte()==5 && input.readUnsignedByte()==1);input.readUnsignedByte()
                        val host=when(input.readUnsignedByte()) {
                            1->ByteArray(4).also(input::readFully).joinToString("."){(it.toInt() and 255).toString()}
                            3->ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                            else->error("unexpected address type")
                        }
                        input.readUnsignedShort();out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
                        val request=ByteArray(42);input.readFully(request)
                        if(host=="149.154.167.51") {slow.incrementAndGet();firstSeen.countDown();while(input.read()>=0) {} }
                        else {
                            check(host=="149.154.175.50");fast.incrementAndGet()
                            check(firstSeen.await(1,TimeUnit.SECONDS))
                            val frame=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
                                .putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(request.copyOfRange(26,42))
                                .put(ByteArray(16){1}).put(8).put(ByteArray(8).apply {this[7]=15}).put(ByteArray(3))
                                .putInt(0x1cb5c415).putInt(1).putLong(1).array()
                            out.write(21);out.write(frame);out.flush()
                        }
                    }} catch(e:Exception) {if(!stop.get() && e !is SocketException && e !is java.io.EOFException) error.compareAndSet(null,e)}
                }
            } catch(e:SocketException) {if(!stop.get()) error.compareAndSet(null,e)}
        }
        try {
            val first=TelegramProbe.measure(listener.localPort,2000)
            assertEquals(true,first.ok);assertEquals(1,first.targetIndex)
            val series=StabilitySeriesProbe.collectTelegram(first,{1800},3000) {budget,target -> TelegramProbe.measureTarget(listener.localPort,budget,null,target)}
            assertEquals(true,series.stable);assertEquals(1,slow.get());assertEquals(3,fast.get())
            assertNull(TelegramProbe.measureTarget(listener.localPort,1000,null,99).ok)
        } finally {
            stop.set(true);listener.close();server.join(1000);jobs.forEach {it.join(1000)}
        }
        error.get()?.let {throw AssertionError("fixture failed",it)}
    }
}
