package com.vlesscardvpn.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Only this app's coarse search outcomes. No endpoints, IDs, carrier names, messages or raw exception text. */
object AutoDiagnostics {
    fun event(status: String, reason: String, durationMs: Long): JSONObject {
        require(status in setOf("PASS","UNKNOWN","CANCELLED","STALE"))
        require(reason in setOf("NONE","NO_CANDIDATE","NETWORK_CHANGED","CANCELLED"))
        return JSONObject().put("scope","auto").put("status",status).put("reason",reason)
            .put("duration_ms",(durationMs.coerceIn(0,120000) / 100 * 100).toInt())
    }
    fun family(key: String): String = when {
        key == "Wi-Fi" -> "wifi"
        key.startsWith("Моб.: ") -> "mobile"
        key == "Нет сети" -> "offline"
        else -> "other"
    }
    @Synchronized fun record(ctx: Context, networkFamily: String, status: String, reason: String, durationMs: Long) {
        val p=ctx.getSharedPreferences("auto_diagnostics",0)
        val family=family(networkFamily)
        val old=if(p.getString("family",null)==family) runCatching { JSONArray(p.getString("events","[]")) }.getOrDefault(JSONArray()) else JSONArray()
        val events=JSONArray()
        for(i in maxOf(0,old.length()-15) until old.length()) events.put(old.getJSONObject(i))
        events.put(event(status,reason,durationMs))
        p.edit().putString("family",family).putString("events",events.toString()).apply()
    }
    fun searchEvent(stop:String,waves:Int,counts:Map<String,Int>):JSONObject {
        require(stop in AutoSearchCampaign.Stop.values().map {it.name})
        val fixed=setOf("started","completed","ready","https_unconfirmed","telegram_unconfirmed","telegram_unknown","unfinished")
        val latencyKey=counts.keys.singleOrNull {it.matches(Regex("over_[0-9]{1,4}ms"))}
        require(latencyKey!=null && counts.keys-latencyKey==fixed)
        val limit=latencyKey.removePrefix("over_").removeSuffix("ms").toInt()
        require(limit in 1..AutoLatencyRange.LIMIT)
        val summary=JSONObject()
        counts.forEach {(key,value)->summary.put(key,value.coerceIn(0,100000))}
        return JSONObject().put("latency_limit_ms",limit).put("stop",stop).put("waves",waves.coerceIn(0,100000)).put("ordinary_probes",summary)
    }
    @Synchronized fun recordSearch(ctx:Context,networkFamily:String,stop:String,waves:Int,counts:Map<String,Int>) {
        ctx.getSharedPreferences("auto_diagnostics",0).edit().putString("search_family",family(networkFamily))
            .putString("last_search",searchEvent(stop,waves,counts).toString()).apply()
    }
    /** Old 500ms counters cannot be relabelled as a 1000ms observation. */
    fun compatibleSearch(raw: String?): JSONObject = runCatching { JSONObject(raw ?: "{}") }
        .getOrDefault(JSONObject()).let { if (it.optJSONObject("ordinary_probes")?.has("over_500ms") == true && !it.has("latency_limit_ms")) JSONObject() else it }
    fun report(ctx: Context): String {
        val p=ctx.getSharedPreferences("auto_diagnostics",0)
        return JSONObject().put("schema",4).put("version",com.vlesscardvpn.BuildConfig.VERSION_NAME)
            .put("family",p.getString("family","other"))
            .put("last_search",compatibleSearch(p.getString("last_search","{}")))
            .put("search_family",p.getString("search_family","other"))
            .put("events",runCatching { JSONArray(p.getString("events","[]")) }.getOrDefault(JSONArray())).toString(2)
    }
    fun clear(ctx: Context) {ctx.getSharedPreferences("auto_diagnostics",0).edit().clear().apply()}
}
