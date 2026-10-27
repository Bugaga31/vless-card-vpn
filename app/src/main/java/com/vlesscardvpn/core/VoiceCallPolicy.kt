package com.vlesscardvpn.core

object VoiceCallPolicy {
    const val MODE_NORMAL = 0
    const val MODE_RINGTONE = 1
    const val MODE_IN_CALL = 2
    const val MODE_IN_COMMUNICATION = 3

    fun active(audioMode: Int): Boolean = audioMode == MODE_RINGTONE || audioMode == MODE_IN_CALL || audioMode == MODE_IN_COMMUNICATION
    fun deferRepair(audioMode: Int, consecutiveFailures: Int): Boolean = active(audioMode) && consecutiveFailures < 5
    fun deferRepair(voiceCallActive: Boolean, consecutiveFailures: Int): Boolean = voiceCallActive && consecutiveFailures < 5
    fun deferAutomaticSwitch(audioMode: Int, activeTraffic: Boolean): Boolean = active(audioMode) || activeTraffic
    fun deferAutomaticSwitch(voiceCallActive: Boolean, protectedTraffic: Boolean): Boolean = voiceCallActive || protectedTraffic
}
