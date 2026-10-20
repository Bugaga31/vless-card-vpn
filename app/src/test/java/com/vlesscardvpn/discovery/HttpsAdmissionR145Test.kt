package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class HttpsAdmissionR145Test {
    private val urls = listOf("https://a.invalid/data","https://b.invalid/data","https://c.invalid/data")
    private fun response(request: Request, code: Int=200, type: String="application/octet-stream", size: Int=65536): Call = object: Call {
        override fun request() = request
        override fun execute() = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .header("Content-Type",type).body(ByteArray(size).toResponseBody(type.toMediaType())).build()
        override fun enqueue(callback: Callback) = error("blocking fixture")
        override fun cancel() {}
        override fun isExecuted() = false
        override fun isCanceled() = false
        override fun timeout() = Timeout()
        override fun clone(): Call = response(request,code,type,size)
    }
    private val now = 1000000L
    private fun state() = ServerState(realMs=20,bigOk=true,kbps=4000,speedNetwork="wifi",speedCheckedAt=now,
        httpsNetwork="wifi",httpsCheckedAt=now,httpsHosts=2,serviceNetwork="wifi",serviceCheckedAt=now,serviceTgOk=true)
    @Test fun multipleIndependentHttpsBodiesPassWithoutYoutubeUrl() {
        val requests=mutableListOf<Request>()
        val result=HttpsTransferProbe.check(urls) { requests+=it; response(it) }
        assertTrue(result.passed);assertEquals(2,result.hosts);assertEquals(131072,result.bytes)
        assertEquals(2,requests.size);assertTrue(result.kbps>0)
        assertTrue(requests.all { it.header("Range")=="bytes=0-65535" && it.url.isHttps })
    }
    @Test fun failedFirstControlFallsBackToOtherIndependentHosts() {
        val result=HttpsTransferProbe.check(urls) { response(it,if(it.url.host=="a.invalid")503 else 200) }
        assertTrue(result.passed);assertEquals(2,result.hosts)
    }
    @Test fun html204AndShortTransferAreNotSuccessfulDataProof() {
        val result=HttpsTransferProbe.check(urls) { when(it.url.host) {
            "a.invalid" -> response(it,204,size=0)
            "b.invalid" -> response(it,type="text/html")
            else -> response(it,size=16384)
        } }
        assertFalse(result.passed);assertEquals(0,result.kbps);assertEquals(0,result.hosts)
    }
    @Test fun sameHostDifferentPathsCannotImpersonateIndependentControls() {
        val result=HttpsTransferProbe.check(listOf("https://a.invalid/a","https://a.invalid/b")) { response(it) }
        assertEquals(1,result.hosts);assertFalse(result.passed)
    }
    @Test fun readsAreBoundedEvenIfOriginIgnoresRange() {
        val result=HttpsTransferProbe.check(urls) { response(it,size=1000000) }
        assertEquals(2*HttpsTransferProbe.SAMPLE_BYTES,result.bytes)
    }
    @Test fun allControlsUnavailableRemainInconclusiveWithoutQuarantineVerdict() {
        val result=HttpsTransferProbe.check(urls) { response(it,503) }
        assertEquals(0,result.hosts)
        assertNull(AutoEligibility.connectionVerdict(Probe(0,null,null,httpsChecked=true)))
    }
    @Test fun unknownYoutubeCanConnectButNeverBecomesYoutubePass() {
        val probe=Probe(30,true,null,tgOk=true,kbps=4000,ytReason="player-login-required",httpsChecked=true,httpsHosts=2)
        assertTrue(AutoEligibility.connectionWorks(probe));assertFalse(AutoEligibility.servicesWork(probe))
        assertTrue(ServiceProbePolicy.describe(probe).contains("не подтверждён"))
        assertEquals(listOf(state()),AutoEligibility.connectionPool(listOf(state()),{it},"wifi",now))
        assertTrue(AutoEligibility.servicePool(listOf(state()),{it},"wifi",now).isEmpty())
    }
    @Test fun confirmedServiceFailureStillExcludedDespiteGoodHttps() {
        val probe=Probe(20,true,null,tgOk=false,httpsHosts=2,httpsChecked=true)
        assertFalse(AutoEligibility.connectionWorks(probe))
        assertTrue(AutoEligibility.connectionPool(listOf(state().copy(serviceTgOk=false)),{it},"wifi",now).isEmpty())
    }
    @Test fun oneHostOrPingOnlyNeverAdmitsUnknownServices() {
        assertFalse(AutoEligibility.connectionWorks(Probe(1,null,null,httpsHosts=1,httpsChecked=true)))
        assertTrue(AutoEligibility.connectionPool(listOf(state().copy(httpsHosts=1)),{it},"wifi",now).isEmpty())
    }
    @Test fun oldNetworkMaskExpiredAndFutureHttpsProofCannotAdmit() {
        val s=state()
        for (bad in listOf(s.copy(httpsNetwork="cell"),s.copy(httpsMask="other"),s.copy(httpsCheckedAt=now-300001),s.copy(httpsCheckedAt=now+1)))
            assertTrue(AutoEligibility.connectionPool(listOf(bad),{it},"wifi",now).isEmpty())
    }
    @Test fun fullyServiceProvenPoolNotDilutedByUnknownFallback() {
        val full=state().copy(serviceYtOk=true,kbps=8000)
        assertEquals(listOf(full),AutoEligibility.connectionPool(listOf(state(),full),{it},"wifi",now))
    }
    @Test fun additiveSerializationPreservesLearningAndLegacyNeedsNewProof() {
        val s=state().copy(okCount=18,netEvidence=mapOf("wifi|" to RouteEvidence()))
        val restored=ServerState.fromJson(s.toJson());assertEquals(s,restored)
        val legacy=s.toJson().apply { remove("httpsAt");remove("httpsHosts");remove("httpsNetwork") }
        val old=ServerState.fromJson(legacy);assertEquals(18,old.okCount);assertFalse(AutoEligibility.httpsFresh(old,"wifi",now))
    }
    @Test fun immediateUnknownServiceProofReuseDoesNotInventServiceResults() {
        val p=Probe(20,true,null,tgOk=null,httpsHosts=2,httpsChecked=true)
        assertTrue(RecentServiceProof(p,"wifi","handle",now).matches("wifi","handle",now+1))
        assertFalse(RecentServiceProof(p,"wifi","handle",now).matches("wifi","new",now+1))
        assertNull(p.ytOk);assertNull(p.tgOk)
    }
}
