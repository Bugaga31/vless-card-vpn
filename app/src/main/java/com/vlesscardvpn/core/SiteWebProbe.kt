package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Request

/** Bounded by the caller. No redirects, fallback, or account/media claims. */
object SiteWebProbe {
    fun check(domain:String, makeCall:(Request)->Call):Boolean? {
        require(SiteCheckCatalog.domain(domain)==domain)
        val request=Request.Builder().url("https://$domain/").header("User-Agent",Tester.UA)
            .header("Cache-Control","no-cache").build()
        return makeCall(request).execute().use { SiteCheckCatalog.response(it.code) }
    }
}
