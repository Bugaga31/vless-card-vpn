package com.vlesscardvpn.health

import com.vlesscardvpn.core.Probe
import org.json.JSONArray
import org.json.JSONObject

/** Adapter only: measurements still come from existing bounded SOCKS probes.
 * The coordinator doesn't read payloads, schedule network work or restart the VPN.
 */
object LiveHealthCoordinator {
    fun assess(probe:Probe,dns:Boolean?,requireDns:Boolean,requireTelegram:Boolean,
               requireYouTube:Boolean,strictLatency:Boolean,blocked:Boolean=false):HealthAssessment =
        ModuleHealthPolicy.assess(HealthInput(probe.realMs>0,probe.realMs,probe.bigOk,
            dns,probe.tgOk,probe.tgMs,probe.ytOk),HealthRequirements(
            dns=requireDns,telegram=requireTelegram,youtube=requireYouTube,
            strictLatency=strictLatency,rejectKnownMediaFailure=false),blocked,com.vlesscardvpn.core.AutoLatencyPolicy.MAX_MS)

    /** Fixed schema, enum labels and nonnegative durations only. No free-form messages. */
    fun report(assessment:HealthAssessment):JSONObject = JSONObject()
        .put("schema",1).put("scope","local_socks_probes_not_full_app_tun")
        .put("ready",assessment.ready ?: JSONObject.NULL).put("suggested_action",assessment.action.name)
        .put("modules",JSONArray().apply {assessment.observations.forEach {o->put(JSONObject()
            .put("module",o.module.name).put("status",o.status.name)
            .put("latency_ms",o.latencyMs ?: JSONObject.NULL))}})
        .put("traffic_capture",false).put("ml_ids",false).put("automatic_upload",false)
}
