package com.vlesscardvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real Android JNI + authenticated controlled VLESS/TLS server, not an operator/DPI test. */
@RunWith(AndroidJUnit4::class)
class NativeAutoRuntimeR187Test {
    private lateinit var cert:X509Certificate
    private lateinit var trust:X509TrustManager
    @Before fun setup() {
        val encoded=InstrumentationRegistry.getArguments().getString("fixture_ca_b64") ?: error("Controlled fixture CA is required")
        cert=CertificateFactory.getInstance("X.509").generateCertificate(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT).inputStream()) as X509Certificate
        val keys=KeyStore.getInstance(KeyStore.getDefaultType()).apply {load(null);setCertificateEntry("controlled-fixture",cert)}
        val tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(keys)}
        trust=tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        XrayCore.init(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    private fun server()=Server("controlled-fixture","vless","r163-pool-0.test",24443,
        "00000000-0000-4000-8000-000000000001",security="tls",sni="vpn.test.local",
        pcs=MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(""){"%02x".format(it)},vcn="vpn.test.local")
    private fun client(port:Int):OkHttpClient {
        val tls=SSLContext.getInstance("TLS").apply {init(null,arrayOf(trust),null)}
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust)
            .proxy(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port)))
            .callTimeout(5,TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }
    private fun request(c:OkHttpClient):Int = c.newCall(Request.Builder()
        .url("https://10.0.2.2:18443/generate_204").build()).execute().use {it.code}
    private fun <T> core(s:Server,mask:com.vlesscardvpn.xray.Mask?=null,block:(OkHttpClient)->T):T {
        val port=ServerSocket(0).use {it.localPort};val core=XrayCore.Instance("controlled-r187")
        try {
            core.start(XrayConfigBuilder.testConfig(listOf(s to mask),listOf(port),null))
            assertTrue("Native Xray must be running",core.running)
            val c=client(port)
            try{return block(c)}finally{c.dispatcher.cancelAll();c.connectionPool.evictAll()}
        } finally {core.stop()}
    }
    @Test fun allEightOptInHelloVariantsTransferAuthenticatedTls() {
        val variants=MaskWarmStart.trials(server(),"",com.vlesscardvpn.model.Settings(),8)
        assertEquals(8,variants.size)
        variants.forEach { mask -> core(server(),mask){assertEquals("Hello variant ${mask.id}",204,request(it))} }
    }
    @Test fun threeNativeStartStopCyclesTransferTls() {
        repeat(3){core(server()){assertEquals(204,request(it))}}
    }
    @Test fun wrongServerCertificatePinCannotPass() {
        core(server()){assertEquals(204,request(it))}
        core(server().copy(pcs="00".repeat(32))) {assertTrue("Wrong upstream pin must reject",runCatching{request(it)}.isFailure)}
    }
    @Test fun wrongVlessIdentityCannotPass() {
        core(server()){assertEquals(204,request(it))}
        core(server().copy(secret="00000000-0000-4000-8000-000000000099")) {
            assertTrue("Invalid VLESS identity must fail closed",runCatching{request(it)}.isFailure)
        }
    }
    @Test fun foregroundPermitSurvivesCancellation()=runBlocking {
        val job=launch {ProbeArbiter.withPermit(true){kotlinx.coroutines.delay(60_000)}}
        kotlinx.coroutines.delay(200);job.cancel();job.join()
        withTimeout(3000){ProbeArbiter.withPermit(true){assertTrue(true)}}
        core(server()){assertEquals(204,request(it))}
    }
    @Test fun nativeHttpsAloneCannotProveTelegramReadiness() {
        core(server()) {
            val started=android.os.SystemClock.elapsedRealtime()
            assertEquals(204,request(it))
            val ms=(android.os.SystemClock.elapsedRealtime()-started).coerceIn(1,Int.MAX_VALUE.toLong()).toInt()
            val a=com.vlesscardvpn.health.LiveHealthCoordinator.assess(
                Probe(ms,true,null,tgOk=null),null,false,true,false,true)
            assertNotEquals(true,a.ready)
            assertEquals(com.vlesscardvpn.health.ModuleStatus.UNKNOWN,
                a.observations.single {o->o.module==com.vlesscardvpn.health.HealthModule.TELEGRAM}.status)
            assertTrue(com.vlesscardvpn.health.LiveHealthCoordinator.report(a).getJSONArray("modules").length()==5)
        }
    }
    @Test fun separateDnsFailurePreservesMeasuredNativeHttps() {
        core(server()) {
            assertEquals(204,request(it))
            val a=com.vlesscardvpn.health.LiveHealthCoordinator.assess(
                Probe(1,true,null),false,true,false,false,false)
            assertEquals(false,a.ready)
            assertEquals(com.vlesscardvpn.health.ModuleStatus.PASS,
                a.observations.single {o->o.module==com.vlesscardvpn.health.HealthModule.HTTPS}.status)
            assertEquals(com.vlesscardvpn.health.HealthAction.RECHECK_DNS,a.action)
        }
    }
}
