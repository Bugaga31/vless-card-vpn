package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks

/** Bounded automatic exploration, never random mutation of a live route or the server contract. */
object AutoMaskSearchPolicy {
    fun candidates(servers:List<Server>,round:Int):List<Server> {
        val all=servers.distinctBy {it.id}
        if(all.isEmpty()) return emptyList()
        val offset=((round.coerceAtLeast(0).toLong()*2)%all.size).toInt()
        return (all.drop(offset)+all.take(offset)).take(2)
    }
    fun pool(server:Server,current:String,settings:Settings,available:(DpiEngine)->Boolean):List<Mask> {
        if(server.protocol !in setOf("vless","vmess","trojan") || server.security !in setOf("tls","reality")) return emptyList()
        val allowed=Masks.searchOrder(true,server,settings.maskFamilies,settings.maskFps)
        val gentle=MaskWarmStart.trials(server,current,settings,8)
        val nativeFp=server.fp.ifEmpty {"chrome"}
        val boundedTpws=allowed.filter {it.fingerprint==nativeFp && it.dpi.startsWith("TPWS#HELLO_")}
        return (gentle.take(2)+allowed.filter {it.custom}+boundedTpws+gentle.drop(2)+allowed).distinctBy {it.id}.filter { m ->
            m.id!=current && Masks.compatible(m,server) && m.sni.isEmpty() && m.alpn.isEmpty() &&
                !m.hasNoise && m.mss==0 && !m.mux && !m.tfo && !m.ipv6 &&
                (!m.viaByeDpi || m.dpi!=Masks.CURRENT_DPI && DpiStrategies.byId(m.dpi,settings)?.let {available(it.engine)}==true)
        }
    }
    fun engineReady(mask:Mask,ports:Map<String,Int>,alive:(String)->Boolean):Boolean =
        !mask.viaByeDpi || mask.dpi!=Masks.CURRENT_DPI && ports[mask.dpi]?.let {it in 1024..65535}==true && alive(mask.dpi)
}
