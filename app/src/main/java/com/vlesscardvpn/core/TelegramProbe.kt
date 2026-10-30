package com.vlesscardvpn.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded unauthenticated req_pq_multi/resPQ reachability, NOT an account login or server authentication.
 * Official built-in DCs: telegramdesktop/tdesktop, mtproto_dc_options.cpp.
 * All traffic uses the supplied local SOCKS listener; never direct fallback or account data.
 */
object TelegramProbe {
    data class Result(val ok: Boolean?, val ms: Int)
    private val dc = listOf("149.154.167.51", "149.154.175.50")
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tg-probe-timeout").apply { isDaemon = true } }
    private val random = SecureRandom()

    internal fun request(nonce: ByteArray, now: Long): ByteArray {
        require(nonce.size == 16)
        val body = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong((now / 1000 shl 32) or ((now % 1000) * 4000000 and 0xfffffffcL))
            .putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
        return byteArrayOf(0xef.toByte(), 10) + body
    }

    internal fun validResponse(bytes: ByteArray, nonce: ByteArray): Boolean = runCatching {
        if (nonce.size != 16 || bytes.size !in 76..4096 || bytes.size % 4 != 0) return false
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.long != 0L) return false
        if (b.long and 1L == 0L) return false // server message ids must be odd
        if (b.int != bytes.size - 20 || b.int != 0x05162463) return false
        val got = ByteArray(16); b.get(got)
        if (!java.security.MessageDigest.isEqual(got, nonce)) return false
        val serverNonce=ByteArray(16);b.get(serverNonce)
        if(serverNonce.all { it==0.toByte() }) return false
        val len = b.get().toInt() and 255
        if (len !in 1..8 || b.remaining() < len + (4 - (len + 1) % 4) % 4 + 8) return false
        val pq=ByteArray(len);b.get(pq)
        val number=java.math.BigInteger(1,pq)
        if(number<=java.math.BigInteger.valueOf(4) || !number.testBit(0) || number.isProbablePrime(16)) return false
        repeat((4-(len+1)%4)%4) { if(b.get()!=0.toByte()) return false }
        if (b.int != 0x1cb5c415) return false // TL vector of RSA key fingerprints
        val count = b.int
        if(count !in 1..16 || b.remaining()!=count*8) return false
        val keys=(0 until count).map { b.long }
        keys.none { it==0L } && keys.distinct().size==count
    }.getOrDefault(false)

    /** null = not enough budget/cancelled; false = neither sampled DC answered valid MTProto. */
    fun check(port: Int, budgetMs: Long = 3000): Boolean? = measure(port, budgetMs).ok

    // Two sampled DCs per route; finite shared workers/queue, never a thread per catalogue item.
    private val workers=java.util.concurrent.ThreadPoolExecutor(16,16,0L,TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
        java.util.concurrent.ThreadFactory {r->Thread(r,"tg-dc-race").apply {isDaemon=true}},
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy())

    fun measure(port: Int, budgetMs: Long = 3000, onCancel:((()->Unit)->Unit)?=null): Result {
        val started=System.nanoTime()
        if(port !in 1..65535 || budgetMs<200 || Thread.currentThread().isInterrupted) return Result(null,0)
        val deadline=started+TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceAtMost(3100))
        val resources=ProbeCancellation()
        onCancel?.invoke {resources.close()}
        val completed=java.util.concurrent.ExecutorCompletionService<Boolean?>(workers)
        var received=0;var unknown=false
        try {
            for(host in dc) {
                val future=completed.submit(java.util.concurrent.Callable<Boolean?> {
                    if(resources.isClosed() || Thread.currentThread().isInterrupted) return@Callable null
                    val left=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
                    if(left<100) return@Callable null
                    val allowance=minOf(maxOf(1500L, AutoLatencyPolicy.MAX_MS.toLong()+100L),left).coerceAtLeast(1)
                    val socket=Socket(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port)))
                    resources.add {socket.close()}
                    val attemptStarted=System.nanoTime()
                    val timeout=timer.schedule({runCatching {socket.close()}},allowance,TimeUnit.MILLISECONDS)
                    try {
                        socket.use {s->
                            s.soTimeout=allowance.toInt()
                            s.connect(InetSocketAddress(host,443),allowance.toInt())
                            val nonce=ByteArray(16).also(random::nextBytes)
                            s.getOutputStream().write(request(nonce,System.currentTimeMillis()))
                            val input=java.io.DataInputStream(s.getInputStream())
                            val first=input.readUnsignedByte()
                            val words=if(first==127) input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or (input.readUnsignedByte() shl 16) else first
                            if(words !in 19..1024) false else {
                                val frame=ByteArray(words*4);input.readFully(frame);validResponse(frame,nonce)
                            }
                        }
                    } catch(e:Exception) {
                        if(e is java.io.InterruptedIOException || resources.isClosed() || Thread.currentThread().isInterrupted ||
                            System.nanoTime()-attemptStarted>=TimeUnit.MILLISECONDS.toNanos(allowance) ||
                            System.nanoTime()>=deadline) null else false
                    } finally {timeout.cancel(false);runCatching {socket.close()}}
                })
                resources.add {future.cancel(true)}
            }
            while(received<dc.size) {
                if(resources.isClosed() || Thread.currentThread().isInterrupted) return Result(null,elapsedMs(started))
                val left=deadline-System.nanoTime()
                if(left<=0) return Result(null,elapsedMs(started))
                val f=completed.poll(left,TimeUnit.NANOSECONDS) ?: return Result(null,elapsedMs(started))
                val result=try {f.get()} catch(_:java.util.concurrent.CancellationException) {null}
                    catch(_:java.util.concurrent.ExecutionException) {null}
                received++
                if(result==true) return Result(true,elapsedMs(started))
                if(result==null) unknown=true
            }
            return Result(if(unknown) null else false,elapsedMs(started))
        } catch(_:InterruptedException) {
            Thread.currentThread().interrupt();return Result(null,elapsedMs(started))
        } catch(_:java.util.concurrent.RejectedExecutionException) {
            return Result(null,elapsedMs(started))
        } finally {resources.close()}
    }

    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).toInt().coerceAtLeast(1)
}
