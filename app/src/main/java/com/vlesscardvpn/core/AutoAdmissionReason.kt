package com.vlesscardvpn.core

/** Explains completed probe gates, not a diagnosis of ISP blocking or real app calls. */
object AutoAdmissionReason {
    fun describe(p:Probe,profile:AutoCheckProfile,maxMs:Int,cooling:Boolean=false):String {
        val why=mutableListOf<String>()
        if(p.realMs<=0) why+="HTTPS не подтверждён"
        else if(p.realMs>maxMs) why+="HTTPS выше предела $maxMs мс"
        if(p.bigOk==false) why+="передача данных не прошла"
        fun gate(name:String,value:Boolean?,id:String) {
            if(value!=true) why+=name+if(value==false) " — проверка не прошла" else
                " — "+ServiceProbeStatus.label(true,null,p.serviceNotes[id])
        }
        if(profile.telegram) {
            gate("Telegram",p.tgOk,"telegram")
            if(p.tgOk==true && p.tgMs<=0) why+="время Telegram неизвестно"
            else if(p.tgOk==true && p.tgMs>maxMs) why+="Telegram ${p.tgMs} мс выше предела $maxMs мс"
        }
        if(profile.youtube) gate("YouTube",p.ytOk,"youtube")
        if(profile.discord) gate("Discord",p.discordOk,"discord")
        if(profile.stability) gate("Устойчивость",p.stableOk,"stability")
        if(profile.voice) gate("UDP для звонков",p.callsOk,"voice")
        if(cooling) why+="временная пауза после предыдущих сбоев"
        val header=if(p.realMs>0) "HTTPS ${p.realMs} мс" else "HTTPS без подтверждённого ответа"
        return "$header · "+if(why.isEmpty()) "выбранные проверки прошли" else why.joinToString("; ")
    }
}