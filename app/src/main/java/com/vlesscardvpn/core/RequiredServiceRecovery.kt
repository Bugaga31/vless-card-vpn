package com.vlesscardvpn.core

/** A definite selected-service failure only. Unknown/challenge is not a dead route. */
object RequiredServiceRecovery {
    fun failed(profile:AutoCheckProfile,p:Probe):Boolean =
        profile.telegram && p.tgOk==false || profile.stability && p.stableOk==false ||
        profile.youtube && p.ytOk==false || profile.discord && p.discordOk==false ||
        profile.voice && p.callsOk==false || profile.instagram && p.instagramOk==false
    /**
     * 1.2.136: one failed sample of a selected service (a lost UDP voice train, a slow Discord/YouTube/Instagram
     * answer, one Telegram stability series) on a route whose HTTPS works is measured once more on the same route
     * before recovery switches servers. A single sample used to start a server change, then another — the
     * «постоянные переподключения». Telegram MTProto itself is already retried in place before this point;
     * quality-only series keep their own deferred confirmation.
     */
    fun needsConfirmation(profile:AutoCheckProfile,p:Probe):Boolean =
        p.works && failed(profile,p) && !qualityOnly(profile,p) && !(profile.telegram && p.tgOk==false)
    /** Only successful-response quality failures use confirmation. Unknown reasons and
     * any other definite selected-service failure retain the immediate recovery path. */
    fun qualityOnly(profile:AutoCheckProfile,p:Probe):Boolean =
        profile.stability && p.stableOk==false &&
        p.serviceNotes["stability"] in setOf("STABILITY_SPREAD","STABILITY_LATENCY") &&
        !(profile.telegram && p.tgOk==false || profile.youtube && p.ytOk==false ||
          profile.discord && p.discordOk==false || profile.voice && p.callsOk==false ||
          profile.instagram && p.instagramOk==false)
}
