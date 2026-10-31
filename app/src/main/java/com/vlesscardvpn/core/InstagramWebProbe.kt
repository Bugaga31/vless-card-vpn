package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Request

/** Public web response only. Never visits supplied links or authenticates an account. */
object InstagramWebProbe {
    const val URL="https://www.instagram.com/"
    const val MAX_BYTES=262144
    data class Result(val ok:Boolean?,val reason:String="")
    internal fun inspect(code:Int,type:String,location:String?,body:String):Result {
        if(code in 300..399) return Result(null,if(location.orEmpty().contains("accounts/login") || location.orEmpty().contains("challenge")) "IG_LOGIN" else "IG_REDIRECT")
        if(code!=200) return Result(null,"IG_HTTP")
        if(!type.startsWith("text/html",true)) return Result(null,"IG_FORMAT")
        val html=body.lowercase()
        if("challenge_required" in html || "checkpoint_required" in html) return Result(null,"IG_LOGIN")
        val title=Regex("<title[^>]*>[^<]*instagram[^<]*</title>").containsMatchIn(html)
        val asset=Regex("https://[a-z0-9.-]+\\.cdninstagram\\.com/").containsMatchIn(html)
        return if(title && asset) Result(true) else Result(null,"IG_FORMAT")
    }
    fun check(call:(Request)->Call):Result = try {
        call(Request.Builder().url(URL).header("User-Agent","Mozilla/5.0").header("Cache-Control","no-cache").build()).execute().use {r->
            if(r.code!=200) return inspect(r.code,r.header("Content-Type").orEmpty(),r.header("Location"),"")
            val src=r.body?.byteStream() ?: return Result(null,"IG_FORMAT")
            val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
            while(out.size()<MAX_BYTES) {
                if(Thread.currentThread().isInterrupted) return Result(null,"CANCELLED")
                val n=src.read(buf,0,minOf(buf.size,MAX_BYTES-out.size()));if(n<0) break
                out.write(buf,0,n)
                val result=inspect(r.code,r.header("Content-Type").orEmpty(),null,out.toString("UTF-8"))
                if(result.ok==true || result.reason=="IG_LOGIN") return result
            }
            inspect(r.code,r.header("Content-Type").orEmpty(),null,out.toString("UTF-8"))
        }
    } catch(_:java.io.InterruptedIOException) {Result(null,if(Thread.currentThread().isInterrupted) "CANCELLED" else "TIMEOUT")}
      catch(_:Exception) {Result(null,if(Thread.currentThread().isInterrupted) "CANCELLED" else "ERROR")}
}
