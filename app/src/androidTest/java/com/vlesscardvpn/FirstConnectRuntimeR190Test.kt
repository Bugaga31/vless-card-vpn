package com.vlesscardvpn

import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Loopback SOCKS fixtures only: no public Telegram, subscription, credentials or direct fallback. */
class FirstConnectRuntimeR190Test {
 private class SocksFixture(val slowFirst:Boolean=false,val badAll:Boolean=false,val stallAll:Boolean=false,
     val parallelServices:Boolean=false,val stallHttp:Boolean=false):AutoCloseable {
  val listener=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
  val port get()=listener.localPort
  val sockets=ConcurrentHashMap.newKeySet<Socket>()
  val jobs=CopyOnWriteArrayList<Thread>()
  val errors=AtomicReference<Throwable?>()
  val bothTgSeen=CountDownLatch(2)
  val firstSeen=CountDownLatch(1);val httpSeen=CountDownLatch(1);val tgSeen=CountDownLatch(1)
  val slowClosed=CountDownLatch(1);val allClosed=CountDownLatch(3)
  val calls=AtomicInteger()
  @Volatile var closing=false
  private val accept=thread(isDaemon=true,name="r190-fake-socks") {
   while(!closing) try {
    val socket=listener.accept();sockets.add(socket)
    jobs+=thread(isDaemon=true) {handle(socket)}
   } catch(e:Exception) {if(!closing) errors.compareAndSet(null,e)}
  }
  private fun response(nonce:ByteArray)=ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
   .putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(nonce).put(ByteArray(16){1})
   .put(8).put(ByteArray(8).apply {this[7]=15}).put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(1).array()
  private fun handle(s:Socket) {
   var dc=false;var slow=false
   try {s.use {
    s.soTimeout=4000
    val input=DataInputStream(s.getInputStream());val out=s.getOutputStream()
    check(input.readUnsignedByte()==5);val methods=ByteArray(input.readUnsignedByte());input.readFully(methods)
    out.write(byteArrayOf(5,0));out.flush()
    check(input.readUnsignedByte()==5 && input.readUnsignedByte()==1);input.readUnsignedByte()
    val host=when(val type=input.readUnsignedByte()) {
     1->ByteArray(4).also(input::readFully).joinToString("."){(it.toInt() and 255).toString()}
     3->ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
     else->error("Unexpected address type $type")
    };input.readUnsignedShort()
    out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,1));out.flush()
    dc=host in setOf("149.154.167.51","149.154.175.50")
    if(dc) {
     val req=ByteArray(42);input.readFully(req);check((req[0].toInt() and 255)==239)
     tgSeen.countDown();bothTgSeen.countDown();slow=host=="149.154.167.51" && slowFirst
     if(slow) firstSeen.countDown()
     if(stallAll || slow) {while(input.read()>=0) {};return@use}
     if(slowFirst) {check(firstSeen.await(1500,TimeUnit.MILLISECONDS));check(slowClosed.count==1L){"Fast DC was delayed behind slow DC"}}
     if(parallelServices) check(httpSeen.await(1500,TimeUnit.MILLISECONDS)){"HTTPS did not start concurrently"}
     val nonce=req.copyOfRange(26,42).apply {if(badAll) this[0]=(this[0].toInt() xor 1).toByte()}
     out.write(21);out.write(response(nonce));out.flush()
    } else {
     calls.incrementAndGet();val reader=input.bufferedReader();while(!reader.readLine().isNullOrEmpty()) {}
     httpSeen.countDown()
     if(parallelServices) check(tgSeen.await(1500,TimeUnit.MILLISECONDS)){"Telegram waited behind HTTPS"}
     out.write(if(stallHttp) "HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n".toByteArray()
       else "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
     if(stallHttp) while(input.read()>=0) {}
    }
   }} catch(e:Exception) {if(!closing && e !is SocketException && e !is java.io.EOFException) errors.compareAndSet(null,e)}
   finally {if(slow) slowClosed.countDown();sockets.remove(s);allClosed.countDown()}
  }
  fun verify() {errors.get()?.let {throw AssertionError("SOCKS fixture failed",it)}}
  override fun close() {closing=true;listener.close();sockets.forEach {runCatching{it.close()}};accept.join(1000);jobs.forEach {it.join(1000)};verify()}
 }
 @Test fun fastSecondDcWinsWhileFirstIsStillStalled() {
  SocksFixture(slowFirst=true).use {f->
   val r=TelegramProbe.measure(f.port,2000)
   assertEquals(true,r.ok);assertTrue(r.ms in 1..1300)
   assertTrue("Losing DC socket must close immediately",f.slowClosed.await(1,TimeUnit.SECONDS));f.verify()
  }
 }
 @Test fun invalidNonceOnBothDcsCannotPass() {
  SocksFixture(badAll=true).use {assertEquals(false,TelegramProbe.measure(it.port,1500).ok)}
 }
 @Test fun bothStalledDcsAreUnknownNotPositive() {
  SocksFixture(stallAll=true).use {assertNull(TelegramProbe.measure(it.port,250).ok)}
 }
 @Test fun invalidLocalPortIsUnknown() {assertNull(TelegramProbe.measure(0).ok);assertNull(TelegramProbe.measure(65536).ok)}
 @Test fun insufficientBudgetDoesNotStartNetworkProbe() {assertEquals(0,TelegramProbe.measure(1080,50).ms)}
 @Test fun interruptedCallerDoesNotStartNetworkProbe() {
  val result=AtomicReference<TelegramProbe.Result>();val t=thread {Thread.currentThread().interrupt();result.set(TelegramProbe.measure(1080))};t.join(1000)
  assertFalse(t.isAlive);assertNull(result.get().ok);assertEquals(0,result.get().ms)
 }
 @Test fun cancellationRegistryClosesEveryHookOnce() {
  val c=ProbeCancellation();val n=AtomicInteger();repeat(3){c.add {n.incrementAndGet()}};c.close();c.close();assertEquals(3,n.get())
 }
 @Test fun hookRegisteredAfterCloseIsClosedImmediately() {
  val c=ProbeCancellation();c.close();var closed=false;c.add {closed=true};assertTrue(closed)
 }
 @Test fun oneBrokenCleanupCannotHideOtherSockets() {
  val c=ProbeCancellation();var closed=false;c.add {error("fixture")};c.add {closed=true};c.close();assertTrue(closed)
 }
 @Test fun cleanupRunsOutsideRegistryLock() {
  val c=ProbeCancellation();var nested=false;c.add {c.add {nested=true}};c.close();assertTrue(nested)
 }
 @Test fun parallelRegistrationAndCloseNeverLoseHooks() {
  val c=ProbeCancellation();val n=AtomicInteger();val ts=(0..3).map {thread {repeat(100){c.add {n.incrementAndGet()}}}}
  c.close();ts.forEach {it.join()};assertEquals(400,n.get())
 }
 @Test fun normalProbeCompletionAlsoReleasesOwnedResources()=runBlocking<Unit> {
  val n=AtomicInteger();assertEquals(7,CancellableProbe.run {bind->bind {n.incrementAndGet()};7})
  withTimeout(1000){while(n.get()!=1) delay(1)};assertEquals(1,n.get())
 }
 @Test fun onlineRetryDoesNotSleepSecondsBetweenWaves()=runBlocking<Unit> {
  val waits=mutableListOf<Long>()
  val r=AutoSearchCampaign.runBounded<String>({"wifi"},{true},{0L},{_,_->},pause={waits+=it}) {_,_->null}
  assertEquals(AutoSearchCampaign.Stop.WAVE_LIMIT,r.stop);assertEquals(listOf(200L,200L,200L),waits)
 }
 @Test fun offlinePollingKeepsTwoSecondPause()=runBlocking<Unit> {
  var now=0L;val waits=mutableListOf<Long>()
  AutoSearchCampaign.runBounded<String>({"wifi"},{false},{now},{_,_->},pause={waits+=it;now=45000}) {_,_->fail("offline probe");null}
  assertEquals(listOf(2000L),waits)
 }
 @Test fun firstProbeIsShortButLatencyThresholdUnchanged() {
  assertEquals(1800L,ConnectPolicy.FIRST_PROBE_MS);assertEquals(1000,AutoLatencyPolicy.MAX_MS)
  assertFalse(AutoLatencyPolicy.accepts(Probe(1001,true,null,tgOk=true,tgMs=100)))
  assertFalse(AutoLatencyPolicy.accepts(Probe(100,true,null,tgOk=null,tgMs=0)))
 }
 @Test fun cancelledRaceDoesNotProduceTelegramSuccess()=runBlocking<Unit> {
  SocksFixture(stallAll=true).use {f->
   var returned=false;val j=launch {CancellableProbe.run {bind->TelegramProbe.measure(f.port,3000,bind)};returned=true}
   withContext(Dispatchers.IO) {assertTrue(f.tgSeen.await(2,TimeUnit.SECONDS))};j.cancelAndJoin();assertFalse(returned)
  }
 }
}
