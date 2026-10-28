package com.vlesscardvpn.core

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Only fixed check names/outcomes, no addresses, network names, IDs, config, exception or log strings. Memory only. */
object ConnectionHistory {
    data class Event(val at:Long,val kind:String,val result:String)
    val events=MutableStateFlow<List<Event>>(emptyList())
    private val last=mutableMapOf<String,String>()
    private val kinds=setOf("https","dns","telegram","youtube")
    @Synchronized fun record(kind:String,value:Boolean?) {
        if(kind !in kinds || value==null) return
        val result=if(value) "PASS" else "FAIL"
        if(last[kind]==result) return
        last[kind]=result
        events.value=(events.value+Event(SystemClock.elapsedRealtime(),kind,result)).takeLast(100)
    }
    @Synchronized fun boundary() {last.clear()}
    @Synchronized fun clear() {events.value=emptyList();last.clear()}
    fun report():String {
        val now=SystemClock.elapsedRealtime()
        return JSONObject().put("schema",1).put("version",com.vlesscardvpn.BuildConfig.VERSION_NAME)
            .put("scope","local_socks_probe_transitions_current_process_only")
            .put("contains_endpoints_or_keys",false).put("events",JSONArray().apply {
                events.value.forEach {put(JSONObject().put("seconds_ago",((now-it.at).coerceAtLeast(0)/1000)).put("check",it.kind).put("result",it.result))}
            }).toString(2)
    }
}
