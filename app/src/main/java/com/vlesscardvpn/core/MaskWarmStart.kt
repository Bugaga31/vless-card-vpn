package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks

/** Native TLS identity, bounded Hello-only fragmentation, no random SNI, noise, MSS clamp or extra engine. */
object MaskWarmStart {
    fun trials(server:Server,current:String,settings:Settings, limit:Int=2):List<Mask> {
        if(server.protocol !in setOf("vless","vmess","trojan") || server.security !in setOf("tls","reality")) return emptyList()
        val allowed=Masks.searchOrder(false,server,settings.maskFamilies,settings.maskFps).map{it.id}.toSet()
        val fp=server.fp.ifEmpty{"chrome"}
        return listOf("$fp.n","$fp.hello.fast","$fp.hello.balanced","$fp.hello.coarse","$fp.hello.mobile")
            .filter{it!=current && it in allowed}.mapNotNull{Masks.byId(it)}
            .filter{!it.viaByeDpi && !it.hasNoise && it.sni.isEmpty() && it.mss==0 && !it.mux}
            .take(limit.coerceIn(0,5))
    }
}
