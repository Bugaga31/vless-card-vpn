package com.vlesscardvpn.core

/** Selected gates, not claims about authenticated apps or calls. HTTPS always remains mandatory. */
data class AutoCheckProfile(val selected:Set<String>) {
    val telegram get() = "telegram" in selected || stability
    val stability get() = "stability" in selected
    val youtube get() = "youtube" in selected
    val discord get() = "discord" in selected
    val instagram get() = "instagram" in selected
    val voice get() = "voice" in selected
    val key get() = selected.sorted().joinToString(",").ifEmpty { "https" }
    fun accepts(p:Probe,maxMs:Int):Boolean = p.works && p.realMs in 1..maxMs &&
        (!telegram || p.tgOk==true && p.tgMs in 1..maxMs) &&
        (!instagram || p.instagramOk==true) && (!youtube || p.ytOk==true) && (!discord || p.discordOk==true) &&
        (!stability || p.stableOk==true) && (!voice || p.callsOk==true)
    fun verdict(p:Probe,maxMs:Int):Boolean? {
        if(!p.works || p.realMs !in 1..maxMs) return false
        val values=listOfNotNull(if(telegram) Pair(p.tgOk,p.tgMs) else null,
            if(instagram) Pair(p.instagramOk,1) else null, if(youtube) Pair(p.ytOk,1) else null, if(discord) Pair(p.discordOk,1) else null,
            if(stability) Pair(p.stableOk,1) else null, if(voice) Pair(p.callsOk,1) else null)
        if(values.any {it.first==false || it.first==true && (it.second<=0 || it.second>maxMs)}) return false
        if(values.any {it.first==null}) return null
        return true
    }
    companion object {
        val allowed=setOf("telegram","stability","voice","discord","youtube","instagram")
        val defaults=setOf("telegram","stability","voice","discord")
        fun of(values:Collection<String>)=AutoCheckProfile(values.filter {it in allowed}.toSet())
    }
}
object AutoChecks {
    val current get()=AutoCheckProfile.of(Store.state.value.settings.autoChecks)
    fun accepts(p:Probe)=current.accepts(p,AutoLatencyPolicy.MAX_MS)
    fun key(profile:AutoCheckProfile,maxMs:Int)=profile.key+"@"+maxMs+(if(profile.discord) "|discord-hello-v1" else "")+
        (if(profile.youtube) "|youtube-ranges-v2" else "")+(if(profile.voice) "|udp-availability-v2" else "")+
        (if(profile.stability) "|telegram-series-v2" else "")+(if(profile.instagram) "|instagram-web-v1" else "")
    fun stamp(p:Probe,profile:AutoCheckProfile,maxMs:Int):String = when {
        profile.accepts(p,maxMs)->key(profile,maxMs)
        (profile.accepts(p,Int.MAX_VALUE) || p.latencyOnlyOk && profile.accepts(p.latencyConsented(),Int.MAX_VALUE))->key(profile,Int.MAX_VALUE)
        else->""
    }
    fun fresh(proof:String,profile:AutoCheckProfile,maxMs:Int,at:Long,now:Long):Boolean =
        proof==key(profile,maxMs) && at>0 && now>=at && now-at<=5*60_000L
    fun accepts(s:com.vlesscardvpn.model.ServerState):Boolean = s.works &&
        fresh(s.autoChecksKey,current,AutoLatencyPolicy.MAX_MS,s.autoChecksAt,System.currentTimeMillis())
    fun rank(s:com.vlesscardvpn.model.ServerState,net:String,now:Long,profile:AutoCheckProfile=current,maxMs:Int=AutoLatencyPolicy.MAX_MS):Int {
        if(s.serviceNetwork!=net || s.serviceMask!=s.maskFor(net) || s.serviceCheckedAt<=0 || now<s.serviceCheckedAt || now-s.serviceCheckedAt>300_000L) return 1
        if(profile.telegram && s.tgOk==false || profile.youtube && s.ytOk==false) return 2
        return if(fresh(s.autoChecksKey,profile,maxMs,s.autoChecksAt,now)) 0 else 1
    }
    fun label(profile:AutoCheckProfile=current):String = (listOf("HTTPS")+listOf(
        "telegram" to "Telegram", "stability" to "устойчивость Telegram", "voice" to "UDP для звонков",
        "discord" to "Discord gateway", "youtube" to "YouTube загрузка видео", "instagram" to "Instagram веб-доступ").filter {it.first in profile.selected}.map {it.second}).joinToString(", ")
}
