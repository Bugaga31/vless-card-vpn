package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.Masks

/** Explicit WARP action is a manual server route, never an automatic direct/standalone fallback. */
object WarpSetupPolicy {
    fun v4Address(value:String):String {
        val host=value.substringBefore('/')
        val parts=host.split('.')
        require(parts.size==4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && it.toIntOrNull() in 0..255 }) { "Invalid WARP IPv4" }
        require('/' !in value || value.substringAfter('/')=="32") { "Invalid WARP prefix" }
        return "$host/32"
    }
    fun select(st:AppState,candidates:List<Server>,network:String,started:Long):AppState {
        val ids=candidates.map {it.id}.toSet()
        val best=st.servers.filter {it.id in ids && Masks.isWarp(it) && ServerOnlyPolicy.userRoute(it)}.filter {s ->
            val p=st.state(s)
            p.works && p.checkedAt>=started && p.serviceNetwork==network && p.serviceMask==p.maskFor(network) &&
                p.serviceCheckedAt>=started && p.tgOk==true && p.realMs in 1..AutoLatencyPolicy.MAX_MS && p.tgMs in 1..AutoLatencyPolicy.MAX_MS
        }.minByOrNull {st.state(it).score} ?: return st
        return st.copy(settings=st.settings.copy(mode=Mode.SERVERS,warpChain=false),
            states=st.servers.associate {it.id to st.state(it).copy(selected=it.id==best.id)})
    }
}
