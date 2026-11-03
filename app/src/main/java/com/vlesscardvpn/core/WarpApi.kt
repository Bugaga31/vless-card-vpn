package com.vlesscardvpn.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owned calls only: cancellation closes this request, never other VPN/probe calls. No redirects at API client. */
object WarpApi {
    const val MAX_BODY = 64 * 1024L
    data class Reply(val code:Int,val text:String)
    suspend fun request(client:OkHttpClient,request:Request):Reply = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val reply=response.use { r ->
                        val body=r.body ?: throw IOException("Empty WARP response")
                        if(body.contentLength()>MAX_BODY) throw IOException("WARP response too large")
                        val source=body.source()
                        if(source.request(MAX_BODY+1)) throw IOException("WARP response too large")
                        Reply(r.code,source.readUtf8())
                    }
                    if(continuation.isActive) continuation.resume(reply)
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
