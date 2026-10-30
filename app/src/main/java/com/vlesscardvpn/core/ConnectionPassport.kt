package com.vlesscardvpn.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Coarse local probe outcomes only. Never takes endpoint URLs, keys, IDs, logs or exception text. */
object ConnectionPassport {
    data class Check(val value: Boolean? = null, val at: Long = 0L)
    data class Snapshot(val https: Check = Check(), val dns: Check = Check(), val telegram: Check = Check(), val youtube: Check = Check())
    val state = MutableStateFlow(Snapshot())
    data class ModuleFrame(val assessment:com.vlesscardvpn.health.HealthAssessment,val at:Long,
        val sampledAt:Map<com.vlesscardvpn.health.HealthModule,Long> = assessment.observations.associate {it.module to at})
    val modules = MutableStateFlow<ModuleFrame?>(null)
    @Synchronized fun fullModules(assessment:com.vlesscardvpn.health.HealthAssessment) {
        modules.value=ModuleFrame(assessment,SystemClock.elapsedRealtime())
    }
    /** Light results supersede old full-check results, without renewing unsampled modules. */
    @Synchronized fun lightModules(probe:Probe, sampledTelegram:Boolean, strictLatency:Boolean) {
        val old=modules.value
        val measured=com.vlesscardvpn.health.LiveHealthCoordinator.assess(probe,null,
            requireDns=false,requireTelegram=sampledTelegram,requireYouTube=false,strictLatency=strictLatency)
            .observations.filter {it.module==com.vlesscardvpn.health.HealthModule.HTTPS ||
                sampledTelegram && it.module==com.vlesscardvpn.health.HealthModule.TELEGRAM}
        val now=SystemClock.elapsedRealtime()
        val merged=com.vlesscardvpn.health.ModuleEvidencePolicy.merge(old?.assessment,old?.sampledAt.orEmpty(),measured,now)
        modules.value=ModuleFrame(merged.assessment,now,merged.sampledAt)
    }
    @Synchronized fun dnsModule(ok:Boolean) {
        val old=modules.value
        val now=SystemClock.elapsedRealtime()
        val measured=listOf(com.vlesscardvpn.health.ModuleObservation(com.vlesscardvpn.health.HealthModule.DNS,
            if(ok) com.vlesscardvpn.health.ModuleStatus.PASS else com.vlesscardvpn.health.ModuleStatus.FAIL))
        val merged=com.vlesscardvpn.health.ModuleEvidencePolicy.merge(old?.assessment,old?.sampledAt.orEmpty(),measured,now)
        modules.value=ModuleFrame(merged.assessment,now,merged.sampledAt)
    }
    /** Missing Tor circuit is unknown service evidence, never a cached green DC result. */
    @Synchronized fun torPending() {
        val now=SystemClock.elapsedRealtime()
        val old=modules.value
        val measured=listOf(com.vlesscardvpn.health.HealthModule.HTTPS,com.vlesscardvpn.health.HealthModule.TELEGRAM,
            com.vlesscardvpn.health.HealthModule.YOUTUBE).map {com.vlesscardvpn.health.ModuleObservation(it,com.vlesscardvpn.health.ModuleStatus.UNKNOWN)}
        val merged=com.vlesscardvpn.health.ModuleEvidencePolicy.merge(old?.assessment,old?.sampledAt.orEmpty(),measured,now)
        modules.value=ModuleFrame(merged.assessment,now,merged.sampledAt)
        state.value=state.value.copy(https=Check(null,now),telegram=Check(null,now),youtube=Check(null,now))
    }
    private fun check(v: Boolean?) = Check(v,SystemClock.elapsedRealtime())
    @Synchronized fun clear() { state.value = Snapshot(); modules.value=null; AutoCheckEvidence.clear(); TelegramStability.clear(); ConnectionHistory.boundary() }
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
        val frame=modules.value
        val now=SystemClock.elapsedRealtime()
        val modules=frame?.takeIf {now>=it.at}?.let {com.vlesscardvpn.health.LiveHealthCoordinator.report(it.assessment)
            .put("age_seconds",(now-it.at)/1000).put("stale",it.assessment.observations.any {o ->
                val at=it.sampledAt[o.module]; o.status!=com.vlesscardvpn.health.ModuleStatus.DISABLED &&
                    (at==null || now<at || now-at>90_000L)})
            .put("module_age_seconds",JSONObject().apply {it.sampledAt.forEach {(module,at)->
                put(module.name,if(now>=at) (now-at)/1000 else JSONObject.NULL)}})}
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
