package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

object ServerBrowser {
    val sorts=listOf("auto" to "По качеству","latency" to "По задержке","speed" to "По скорости","name" to "По имени","favorite" to "Избранные сверху")
    val services=listOf("any" to "Любые результаты","telegram" to "Telegram: свежий ответ","youtube" to "YouTube: свежий ответ","insecure" to "Проверить TLS")
    fun matches(s:Server,st:ServerState,protocol:String,service:String,network:String,now:Long):Boolean {
        if(protocol.isNotEmpty() && s.protocol!=protocol) return false
        return when(service) {
            "telegram","youtube" -> AutoEligibility.serviceRank(st,network,now,setOf(service))==0
            "insecure" -> s.insecure
            else -> true
        }
    }
    fun comparator(order:String,state:(Server)->ServerState,favorites:Set<String>):Comparator<Server> = when(order) {
        "name" -> compareBy<Server>{it.name.lowercase(java.util.Locale.ROOT)}.thenBy{it.id}
        "latency" -> compareBy<Server>{state(it).realMs.takeIf{n->n>0} ?: Int.MAX_VALUE}.thenBy{it.id}
        "speed" -> compareByDescending<Server>{state(it).kbps.takeIf{n->n>0} ?: -1}.thenBy{it.id}
        "favorite" -> compareBy<Server>{it.id !in favorites}.thenBy{state(it).score}.thenBy{it.id}
        else -> compareBy<Server>{when {state(it).selected->0;state(it).works->1;state(it).tcpMs>0 && state(it).realMs<0->2;state(it).realMs<0 && state(it).tcpMs<0->3;else->4}}
            .thenBy{state(it).score}.thenBy{state(it).tcpMs.takeIf{n->n>0} ?: Int.MAX_VALUE}.thenBy{it.id}
    }
    fun age(at:Long,now:Long):String = when {
        at<=0->"не проверен"
        now<at->"время не совпадает"
        now-at<60_000->"меньше минуты назад"
        now-at<3_600_000->"${(now-at)/60_000} мин назад"
        now-at<86_400_000->"${(now-at)/3_600_000} ч назад"
        else->"${(now-at)/86_400_000} дн назад"
    }
}
