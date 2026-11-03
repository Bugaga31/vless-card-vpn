package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.io.DataInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class FirstConnectivityR219Test {
    private class Fixture(val mode:String):AutoCloseable {
        val listener=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val request=CountDownLatch(1)
        val socket=AtomicReference<Socket?>()
        val error=AtomicReference<Throwable?>()
        @Volatile var closing=false
        val job=thread(isDaemon=true) {
            try { listener.accept().use {s->
                socket.set(s);s.soTimeout=6000
                val input=DataInputStream(s.getInputStream());val out=s.getOutputStream()
                check(input.readUnsignedByte()==5);input.readFully(ByteArray(input.readUnsignedByte()))
                out.write(byteArrayOf(5,0));out.flush()
                check(input.readUnsignedByte()==5);check(input.readUnsignedByte()==1);input.readUnsignedByte()
                when(input.readUnsignedByte()) {
                    1->input.readFully(ByteArray(4))
                    3->input.readFully(ByteArray(input.readUnsignedByte()))
                    4->input.readFully(ByteArray(16))
                    else->kotlin.error("SOCKS address type")
                }
                input.readUnsignedShort();out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
                if(mode=="tls") {
                    check(input.readUnsignedByte()==22) // TLS handshake record, no server response.
                    request.countDown();while(input.read()>=0) {}
                } else {
                    val reader=input.bufferedReader();while(!reader.readLine().isNullOrEmpty()) {}
                    request.countDown()
                    if(mode=="fast") {out.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());out.flush()}
                    else {
                        if(mode=="body") {out.write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\n".toByteArray());out.flush()}
                        while(input.read()>=0) {}
                    }
                }
            }} catch(e:Exception) {if(!closing && e !is SocketException && e !is java.io.EOFException) error.set(e)}
        }
        override fun close() {
            closing=true;listener.close();socket.get()?.let{runCatching{it.close()}};job.join(1500)
            assertFalse("fixture thread leaked",job.isAlive)
            error.get()?.let{throw AssertionError("fake SOCKS failed",it)}
        }
    }
    private fun stalled(mode:String) {
        Fixture(mode).use {f->
            val start=System.nanoTime()
            val scheme=if(mode=="tls") "https" else "http"
            val p=Tester.probeSocks(f.listener.localPort,"$scheme://probe.invalid/check",big=false,youtube=false,
                telegram=false,attempts=1,budgetMs=5000,connectivityBudgetMs=300)
            val elapsed=(System.nanoTime()-start)/1000000
            assertTrue("request never reached fixture",f.request.await(1,TimeUnit.SECONDS))
            assertFalse("blackhole must not pass",p.works)
            assertTrue("first request consumed full service budget: $elapsed",elapsed<2500)
        }
    }
    @Test fun onlyForegroundAutoGetsShortFirstRequest() {
        assertEquals(3100L,FirstConnectivityBudget.cap(true,true))
        assertEquals(2500L,FirstConnectivityBudget.cap(true,true,800))
        assertEquals(0L,FirstConnectivityBudget.cap(false,true))
        assertEquals(0L,FirstConnectivityBudget.cap(true,false))
    }
    @Test fun overallDeadlineStillWins() {assertEquals(120L,FirstConnectivityBudget.remaining(120,2500));assertEquals(1L,FirstConnectivityBudget.remaining(-1,2500))}
    @Test fun disabledCapPreservesServiceBudget() {assertEquals(6500L,FirstConnectivityBudget.remaining(6500,0));assertEquals(2500L,FirstConnectivityBudget.remaining(6500,2500))}
    @Test fun stalledTlsClientHelloFreesSlot() {stalled("tls")}
    @Test fun stalledHttpHeadersFreeSlot() {stalled("http")}
    @Test fun stalledResponseBodyFreesSlot() {stalled("body")}
    @Test fun quick204StillPasses() {
        Fixture("fast").use {f->
            val p=Tester.probeSocks(f.listener.localPort,"http://probe.invalid/generate_204",big=false,youtube=false,
                telegram=false,attempts=1,budgetMs=5000,connectivityBudgetMs=1500)
            assertTrue(p.works);assertTrue(p.realMs>0)
        }
    }
}
