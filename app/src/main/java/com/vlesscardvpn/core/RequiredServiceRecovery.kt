package com.vlesscardvpn.core

/** A definite selected-service failure only. Unknown/challenge is not a dead route. */
object RequiredServiceRecovery {
    fun failed(profile:AutoCheckProfile,p:Probe):Boolean =
        profile.telegram && p.tgOk==false || profile.stability && p.stableOk==false ||
        profile.youtube && p.ytOk==false || profile.discord && p.discordOk==false ||
        profile.voice && p.callsOk==false || profile.instagram && p.instagramOk==false
}
