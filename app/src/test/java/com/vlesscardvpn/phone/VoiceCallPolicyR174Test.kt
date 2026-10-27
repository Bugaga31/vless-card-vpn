package com.vlesscardvpn.phone

import com.vlesscardvpn.core.VoiceCallPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallPolicyR174Test {
    @Test fun `VoIP and cellular call audio modes protect the live connection`() {
        assertTrue(VoiceCallPolicy.active(VoiceCallPolicy.MODE_IN_CALL))
        assertTrue(VoiceCallPolicy.active(VoiceCallPolicy.MODE_IN_COMMUNICATION))
        assertFalse(VoiceCallPolicy.active(0))
    }

    @Test fun `an active call or sustained user traffic defers automatic changes`() {
        assertTrue(VoiceCallPolicy.deferAutomaticSwitch(VoiceCallPolicy.MODE_IN_COMMUNICATION, false))
        assertTrue(VoiceCallPolicy.deferAutomaticSwitch(0, true))
        assertFalse(VoiceCallPolicy.deferAutomaticSwitch(0, false))
    }
}
