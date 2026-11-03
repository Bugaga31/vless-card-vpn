package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** HTTPS-history and current Auto admission are different facts; never silently equate them. */
object AutoReadiness {
    fun describe(server:Server,s:ServerState,profile:AutoCheckProfile,limit:Int,net:String,now:Long,cooling:Boolean=false):String {
        if(!s.works) return "HTTPS не подтверждён"
        if(!AutoEligibility.vlessServer(server)) return "Не подходит под ограничения Авто"
        if(s.serviceNetwork!=net) return "Нужна проверка в этой сети"
        if(s.serviceMask!=s.maskFor(net)) return "Нужна проверка этой маскировки"
        if(cooling) return "Временная пауза после сбоев"
        if(profile.telegram && s.tgOk!=true) return "Telegram не подтверждён"
        if(profile.youtube && s.ytOk!=true) return "YouTube не подтверждён"
        val timingOk=s.realMs in 1..limit && (!profile.telegram || s.tgMs in 1..limit)
        if(timingOk && AutoChecks.fresh(s.autoChecksKey,profile,limit,s.autoChecksAt,now))
            return "Проверки Авто пройдены"
        if(SlowRoutePolicy.measured(s,profile,limit,net,now))
            return "Выше предела — можно подтвердить при поиске"
        return "Нужна свежая проверка выбранных сервисов"
    }
}