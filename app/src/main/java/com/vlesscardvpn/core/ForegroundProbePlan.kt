package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Preserve the admitted source window, but do not make ready routes wait for certificate acquisition.
 * Deferred candidates still go through ordinary pinning and full HTTPS/MTProto checks.
 */
object ForegroundProbePlan {
    fun needsCertificate(s:Server):Boolean = s.insecure && s.pcs.isEmpty() &&
        (s.security == "tls" || s.protocol == "hysteria2")
    fun cohorts(candidates:List<Server>,foreground:Boolean):List<List<Server>> {
        if(candidates.isEmpty()) return emptyList()
        if(!foreground) return listOf(candidates)
        val (pending,ready)=candidates.partition(::needsCertificate)
        return listOf(ready,pending).filter {it.isNotEmpty()}
    }
    fun staggerMs(foreground:Boolean,stopAfter:Int):Long = if(!foreground && stopAfter==1) 120L else 0L
    suspend fun run(candidates:List<Server>,foreground:Boolean,valid:()->Boolean,
        probe:suspend (List<Server>)->Int):Int {
        var passed=0
        for(cohort in cohorts(candidates,foreground)) {
            currentCoroutineContext().ensureActive()
            if(!valid() || (foreground && passed>0)) break
            passed+=probe(cohort)
        }
        return passed
    }
}
