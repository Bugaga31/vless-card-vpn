package com.vlesscardvpn.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*
import org.json.JSONObject

/** Public control-channel handshake only; never IDENTIFY, account credentials or voice-session claims. */
object DiscordGatewayProbe {
    data class Result(val ok:Boolean?,val ms:Int)
    fun opcode(text:String):Int? = runCatching {
        if(text.length>16384) null else JSONObject(text).optInt("op",-1).takeIf {it>=0}
    }.getOrNull()
    fun check(port:Int,onCancel:((()->Unit)->Unit)?=null):Result {
        if(port !in 1..65535) return Result(null,0)
        val client=OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port)))
            .followRedirects(false).followSslRedirects(false).connectTimeout(2,TimeUnit.SECONDS)
            .readTimeout(3,TimeUnit.SECONDS).build()
        val done=CountDownLatch(1)
        val acknowledged=AtomicBoolean(false)
        val cancelled=AtomicBoolean(false)
        val hello=AtomicBoolean(false)
        val sentAt=java.util.concurrent.atomic.AtomicLong(0)
        val elapsed=java.util.concurrent.atomic.AtomicInteger(0)
        val socket=client.newWebSocket(Request.Builder().url("wss://gateway.discord.gg/?v=10&encoding=json").build(),object:WebSocketListener() {
            override fun onMessage(ws:WebSocket,text:String) {
                when(opcode(text)) {
                    10 -> if(hello.compareAndSet(false,true)) {
                        sentAt.set(System.nanoTime())
                        if(!ws.send("{\"op\":1,\"d\":null}")) done.countDown()
                    }
                    11 -> if(hello.get() && sentAt.get()>0 && acknowledged.compareAndSet(false,true)) {
                        elapsed.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-sentAt.get()).toInt().coerceAtLeast(1))
                        done.countDown()
                    }
                }
            }
            override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) {runCatching {response?.close()};done.countDown()}
            override fun onClosed(ws:WebSocket,code:Int,reason:String) {done.countDown()}
        })
        onCancel?.invoke {cancelled.set(true);socket.cancel();done.countDown()}
        return try {
            val finished=done.await(3500,TimeUnit.MILLISECONDS)
            Result(if(cancelled.get() || Thread.currentThread().isInterrupted) null else finished && acknowledged.get(),elapsed.get())
        } catch(_:InterruptedException) {Thread.currentThread().interrupt();Result(null,0)}
        finally {socket.cancel();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
    }
}
