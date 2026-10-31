package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject

/** Public API evidence only: not account login, WebSocket session or a voice call. */
object DiscordApiProbe {
    const val URL = "https://discord.com/api/v10/gateway"
    data class Result(val ok:Boolean?,val reason:String="")
    fun check(call:(Request)->Call):Result = try {
        call(Request.Builder().url(URL).build()).execute().use {r ->
            // Rate limits, challenges and unexpected formats cannot diagnose a dead route.
            if(r.code!=200) return Result(null,"DISCORD_HTTP")
            val input=r.body?.byteStream() ?: return Result(null,"DISCORD_FORMAT")
            val bytes=ByteArray(8193);var n=0
            while(n<bytes.size) {
                if(Thread.currentThread().isInterrupted) return Result(null,"CANCELLED")
                val count=input.read(bytes,n,bytes.size-n)
                if(count<0) break
                n+=count
            }
            if(n>8192) return Result(null,"DISCORD_FORMAT")
            val gateway=runCatching {JSONObject(String(bytes,0,n,Charsets.UTF_8)).optString("url")}.getOrNull()
            if(gateway=="wss://gateway.discord.gg" || gateway=="wss://gateway.discord.gg/") Result(true)
            else Result(null,"DISCORD_FORMAT")
        }
    } catch(_:java.io.InterruptedIOException) {
        Result(null,if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT")
    } catch(_:Exception) {
        Result(null,if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")
    }
}
