package com.vlesscardvpn.core

import com.vlesscardvpn.health.ModuleObservation
import com.vlesscardvpn.health.ModuleStatus

data class ProbePresentation(val status:String,val label:String,val latencyMs:Int?=null)
/** Presentation only, never starts probes or infers carrier billing / DPI resistance. */
object ConnectionPresentationPolicy {
    fun probe(observation:ModuleObservation?,sampledAt:Long?,now:Long):ProbePresentation {
        if(observation==null || sampledAt==null) return ProbePresentation("UNKNOWN","Не проверено")
        if(now<sampledAt || now-sampledAt>90_000L) return ProbePresentation("STALE","Замер устарел")
        val label=when(observation.status) {
            ModuleStatus.PASS->"Ответ получен";ModuleStatus.FAIL->"Нет ответа";ModuleStatus.UNKNOWN->"Не проверено"
            ModuleStatus.SLOW->"Медленно";ModuleStatus.DISABLED->"Не требуется"
        }
        return ProbePresentation(observation.status.name,label,observation.latencyMs?.takeIf {
            observation.status in setOf(ModuleStatus.PASS,ModuleStatus.SLOW) && it>0})
    }
    fun heading(state:Tunnel.State,checked:Boolean?):String = when(state) {
        Tunnel.State.IDLE->"Связь начинается здесь"
        Tunnel.State.CONNECTING->"Подбираем маршрут"
        Tunnel.State.ERROR->"Не удалось подключиться"
        Tunnel.State.CONNECTED->when(checked) {true->"Маршрут подключён";false->"Связь требует внимания";null->"Проверяем соединение"}
    }
}
