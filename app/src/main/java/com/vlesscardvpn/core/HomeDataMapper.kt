package com.vlesscardvpn.core

/** Maps actual Probe fields. There are no invented per-service fields on ServerState. */
object HomeDataMapper {
    fun proofCurrent(profileMatches:Boolean,limitMatches:Boolean,at:Long,now:Long,pathMatches:Boolean,
        epochMatches:Boolean,revisionMatches:Boolean,coreStarted:Boolean,engineAvailable:Boolean):Boolean =
        profileMatches && limitMatches && HomePresentation.fresh(at,now) && pathMatches && epochMatches &&
            revisionMatches && coreStarted && engineAvailable
    fun services(profile:AutoCheckProfile,probe:Probe?,current:Boolean):List<HomePresentation.Service> {
        fun chip(id:String,title:String,required:Boolean,value:Boolean?)=HomePresentation.Service(id,title,
            HomePresentation.verdict(required,value,probe!=null,current),
            if(required && probe!=null && current) HomePresentation.note(probe.serviceNotes[id]) else "")
        return listOf(
            chip("https","HTTPS",true,probe?.let {if(it.realMs<0) null else it.works}),
            chip("telegram","Telegram",profile.telegram,probe?.tgOk),
            chip("stability","Устойчивость TG",profile.stability,probe?.stableOk),
            chip("youtube","YouTube · видео",profile.youtube,probe?.ytOk),
            chip("discord","Discord · gateway",profile.discord,probe?.discordOk),
            chip("voice","Звонки · UDP",profile.voice,probe?.callsOk),
            chip("instagram","Instagram · веб",profile.instagram,probe?.instagramOk))
    }
}
