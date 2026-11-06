package com.vlesscardvpn.core

/** A rising Android validation signal is a hint to retry, never proof that the VPN route works. */
class NetworkValidationWake {
    private var handle: String? = null
    private var validated: Boolean? = null

    @Synchronized fun observe(observed: String, selected: String?, nowValidated: Boolean): Boolean {
        if (observed.isBlank() || observed != selected) return false
        if (handle != observed) {
            handle = observed
            validated = nowValidated
            return false
        }
        val recovered = validated == false && nowValidated
        validated = nowValidated
        return recovered
    }

    @Synchronized fun reset() { handle = null; validated = null }
}
