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
    fun report(ctx: Context): String {
        val p=ctx.getSharedPreferences("auto_diagnostics",0)
        return JSONObject().put("schema",1).put("version",com.vlesscardvpn.BuildConfig.VERSION_NAME)
            .put("family",p.getString("family","other"))
            .put("events",runCatching { JSONArray(p.getString("events","[]")) }.getOrDefault(JSONArray())).toString(2)
    }
    fun clear(ctx: Context) {ctx.getSharedPreferences("auto_diagnostics",0).edit().clear().apply()}
}
