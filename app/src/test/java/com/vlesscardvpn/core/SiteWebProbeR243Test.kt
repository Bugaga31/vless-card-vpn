package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SiteWebProbeR243Test {
    private class FakeCall(val req:Request,val code:Int):Call {
        override fun request()=req
        override fun execute()=Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test").body("".toResponseBody()).build()
        override fun enqueue(responseCallback:Callback) { throw UnsupportedOperationException() }
        override fun cancel() {}
        override fun isExecuted()=false
        override fun isCanceled()=false
        override fun timeout()=Timeout()
        override fun clone():Call=FakeCall(req,code)
    }
    @Test fun buildsHttpsForExactHost() {
        var request:Request?=null
        assertEquals(true,SiteWebProbe.check("github.com") {request=it;FakeCall(it,200)})
        assertEquals("https",request!!.url.scheme);assertEquals("github.com",request!!.url.host)
        assertEquals("/",request!!.url.encodedPath)
    }
    @Test fun redirectsAndRejectionsRemainUnknown() {
        listOf(301,302,403,404,429,500).forEach {code -> assertNull(SiteWebProbe.check("github.com") {FakeCall(it,code)})}
    }
    @Test fun invalidInputCannotCreateRequest() {
        try {SiteWebProbe.check("github.com/path") {throw AssertionError("must not call")};fail("accepted invalid host")}
        catch(_:IllegalArgumentException) {}
    }
    @Test fun cancellationOrIoFailureIsNotConvertedIntoPass() {
        try { SiteWebProbe.check("github.com") {throw IOException("unreachable")};fail("swallowed failure") }
        catch(_:IOException) {}
    }
}
