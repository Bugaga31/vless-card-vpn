package com.vlesscardvpn.core

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Anonymous Gateway HELLO only. No Identify, account token, heartbeat loop or voice claim. */
object DiscordRouteProbe {
    private const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    fun check(client:OkHttpClient, call:(Request)->Call, remaining:()->Long,
              onCancel:((()->Unit)->Unit)?=null):DiscordApiProbe.Result {
        val api=DiscordApiProbe.check(call)
        if(api.ok!=true) return api
        if(Thread.currentThread().isInterrupted) return DiscordApiProbe.Result(null,"CANCELLED")
        if(remaining()<=1) return DiscordApiProbe.Result(null,"TIMEOUT")
        return hello(client,GATEWAY,remaining(),onCancel)
    }
    internal fun validHello(text:String):Boolean = runCatching {
        if(text.length>65536) return false
        val j=JSONObject(text)
        j.optInt("op",-1)==10 && (j.optJSONObject("d")?.optLong("heartbeat_interval",0) ?: 0) in 1..300000
    }.getOrDefault(false)

    internal fun hello(client:OkHttpClient,url:String,budgetMs:Long,
                       onCancel:((()->Unit)->Unit)?=null):DiscordApiProbe.Result {
        if(Thread.currentThread().isInterrupted) return DiscordApiProbe.Result(null,"CANCELLED")
        val done=CountDownLatch(1)
        val result=AtomicReference<DiscordApiProbe.Result?>(null)
        val socket=AtomicReference<WebSocket?>(null)
        fun finish(value:DiscordApiProbe.Result) { if(result.compareAndSet(null,value)) done.countDown() }
        val listener=object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                finish(if(validHello(text)) DiscordApiProbe.Result(true) else DiscordApiProbe.Result(null,"DISCORD_GATEWAY"))
            }
            override fun onMessage(webSocket:WebSocket,bytes:okio.ByteString) { finish(DiscordApiProbe.Result(null,"DISCORD_GATEWAY")) }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { finish(DiscordApiProbe.Result(null,"DISCORD_GATEWAY")) }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) { finish(DiscordApiProbe.Result(null,"DISCORD_GATEWAY")) }
        }
        try {
            onCancel?.invoke {
                finish(DiscordApiProbe.Result(null,"CANCELLED"));socket.get()?.cancel()
            }
            if(result.get()!=null) return result.get()!!
            socket.set(client.newWebSocket(Request.Builder().url(url).build(),listener))
            // Cancellation may have raced with socket creation.
            if(result.get()!=null) socket.get()?.cancel()
            if(!done.await(budgetMs.coerceIn(1,6500),TimeUnit.MILLISECONDS)) finish(DiscordApiProbe.Result(null,"TIMEOUT"))
            return result.get() ?: DiscordApiProbe.Result(null,"DISCORD_GATEWAY")
        } catch(_:InterruptedException) {
            Thread.currentThread().interrupt();return DiscordApiProbe.Result(null,"CANCELLED")
        } catch(_:Exception) {return DiscordApiProbe.Result(null,"DISCORD_GATEWAY")}
        finally {socket.get()?.cancel()}
    }
}
