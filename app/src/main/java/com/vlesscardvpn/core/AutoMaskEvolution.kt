package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.MaskLab
import org.json.JSONObject
import kotlin.random.Random

/** Small repeatable Hello neighbourhood. No background timer, server-contract mutation or DPI claim. */
object AutoMaskEvolution {
    fun enabled(settings:Settings):Boolean = settings.maskEvolution &&
        (settings.maskFamilies.isEmpty() || "auto" in settings.maskFamilies)

    fun candidates(server:Server,settings:Settings,parents:List<Mask>):List<Mask> {
        if(!enabled(settings) || server.protocol !in setOf("vless","vmess","trojan") ||
            server.security !in setOf("tls","reality")) return emptyList()
        val fp=server.fp.ifEmpty {"chrome"}
        if(settings.maskFps.isNotEmpty() && fp !in settings.maskFps) return emptyList()
        val parent=parents.firstOrNull {it.fingerprint==fp && it.packets=="tlshello" &&
            !it.viaByeDpi && !it.hasNoise && it.sni.isEmpty() && it.alpn.isEmpty() &&
            it.mss==0 && !it.mux && !it.tfo && !it.ipv6 && Masks.compatible(it,server)}
            ?: return emptyList()
        val random=Random((server.id+parent.id).hashCode())
        return (0 until 8).mapNotNull {
            val low=random.nextInt(80,181)
            val high=(low+random.nextInt(40,121)).coerceAtMost(300)
            runCatching {MaskLab.make(JSONObject().put("t","Авто Hello · эксперимент")
                .put("fp",fp).put("p","tlshello").put("l","$low-$high")
                .put("d","0-1").put("ms",random.nextInt(6,13).toString()))}.getOrNull()
        }.distinctBy {it.id}.filter {it.id!=parent.id && Masks.compatible(it,server)}
    }

    /** Retain referenced profiles first. Never leave another saved route pointing at an evicted mask. */
    fun retain(stored:List<String>,winner:Mask,referenced:Set<String>):List<String> {
        val old=MaskLab.load(stored)
        return (old.filter {it.id in referenced}+listOf(winner)+old).distinctBy {it.id}
            .take(MaskLab.CAP).map {MaskLab.store(it)}
    }
}
