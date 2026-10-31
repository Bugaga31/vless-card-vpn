package com.vlesscardvpn.core

/** One alert per recovery incident. Recovery success must be a full, current route verdict. */
class RecoveryAlertPolicy {
    enum class Alert { NONE, QUIET, SOUND }
    private var active=false
    private var lastSound=-1L
    @Synchronized fun begin(now:Long):Alert {
        if(active) return Alert.NONE
        active=true
        val sound=lastSound<0 || now<lastSound || now-lastSound>=60_000L
        if(sound) lastSound=now
        return if(sound) Alert.SOUND else Alert.QUIET
    }
    @Synchronized fun recovered(verified:Boolean):Boolean {
        if(!active || !verified) return false
        active=false;return true
    }
    @Synchronized fun clear() {active=false}
}
