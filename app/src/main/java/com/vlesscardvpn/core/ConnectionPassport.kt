package com.vlesscardvpn.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Coarse local probe outcomes only. Never takes endpoint URLs, keys, IDs, logs or exception text. */
object ConnectionPassport {
    data class Check(val value: Boolean? = null, val at: Long = 0L)
    data class Snapshot(val https: Check = Check(), val dns: Check = Check(), val telegram: Check = Check(), val youtube: Check = Check())
    val state = MutableStateFlow(Snapshot())
    private data class ModuleFrame(val assessment:com.vlesscardvpn.health.HealthAssessment,val at:Long)
    @Volatile private var lastFullModules:ModuleFrame?=null
    @Synchronized fun fullModules(assessment:com.vlesscardvpn.health.HealthAssessment) {
        lastFullModules=ModuleFrame(assessment,SystemClock.elapsedRealtime())
    }
    private fun check(v: Boolean?) = Check(v,SystemClock.elapsedRealtime())
    @Synchronized fun clear() { state.value = Snapshot(); lastFullModules=null; ConnectionHistory.boundary() }
    @Synchronized fun full(https: Boolean, dns: Boolean?, telegram: Boolean?, youtube: Boolean?) {
        state.value = Snapshot(check(https),check(dns),check(telegram),check(youtube))
        ConnectionHistory.record("https",https); ConnectionHistory.record("dns",dns)
        ConnectionHistory.record("telegram",telegram); ConnectionHistory.record("youtube",youtube)
    }
    @Synchronized fun light(https: Boolean, telegram: Boolean?, sampledTelegram: Boolean) {
        ConnectionHistory.record("https",https); if(sampledTelegram) ConnectionHistory.record("telegram",telegram)
        val s = state.value
        state.value = s.copy(https=check(https),telegram=if(sampledTelegram) check(telegram) else s.telegram)
    }
    @Synchronized fun dns(ok: Boolean) { state.value = state.value.copy(dns=check(ok)); ConnectionHistory.record("dns",ok) }
    fun status(c: Check): String = when {
        c.at <= 0 || c.value == null -> "UNKNOWN"
        SystemClock.elapsedRealtime() < c.at || SystemClock.elapsedRealtime()-c.at > 90_000L -> "STALE"
        c.value == true -> "PASS"
        else -> "FAIL"
    }
    fun label(c: Check): String = when(status(c)) { "PASS" -> "отвечает"; "FAIL" -> "нет ответа"; "STALE" -> "замер устарел"; else -> "не подтверждено" }
    fun age(c:Check): Long? = if(c.at<=0) null else ((SystemClock.elapsedRealtime()-c.at).coerceAtLeast(0)/1000)
    fun report(): String {
        val s=state.value
        val frame=lastFullModules
        val now=SystemClock.elapsedRealtime()
        val modules=frame?.takeIf {now>=it.at}?.let {com.vlesscardvpn.health.LiveHealthCoordinator.report(it.assessment)
            .put("age_seconds",(now-it.at)/1000).put("stale",now-it.at>90_000L)}
        return JSONObject().put("schema",1).put("version",com.vlesscardvpn.BuildConfig.VERSION_NAME)
            .put("scope","local_socks_chain_not_independent_app_tun_test")
            .put("last_full_module_assessment",modules ?: JSONObject.NULL)
            .put("mask_experiment",MaskExperiment.report()).put("probe_scheduler",ProbeArbiter.report()).put("https",status(s.https)).put("dns_socks_udp",status(s.dns))
            .put("telegram_sampled_mtproto",status(s.telegram)).put("youtube_sampled_media",status(s.youtube))
            .put("sample_age_seconds",JSONObject().put("https",age(s.https) ?: JSONObject.NULL).put("dns",age(s.dns) ?: JSONObject.NULL)
                .put("telegram",age(s.telegram) ?: JSONObject.NULL).put("youtube",age(s.youtube) ?: JSONObject.NULL))
            .put("optional_optimization_paused",SessionHold.active()).put("contains_endpoints_or_keys",false).toString(2)
    }
}
