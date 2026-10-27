package com.vlesscardvpn.phone

import com.vlesscardvpn.core.PowerPolicy
import com.vlesscardvpn.core.VoiceCallPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallContinuityRegressionR174Test {
    @Test fun onlyEstablishedCallModesTriggerCallProtection() {
        assertFalse(VoiceCallPolicy.active(VoiceCallPolicy.MODE_NORMAL))
        assertTrue(VoiceCallPolicy.active(VoiceCallPolicy.MODE_IN_CALL))
        assertTrue(VoiceCallPolicy.active(VoiceCallPolicy.MODE_IN_COMMUNICATION))
        assertTrue(VoiceCallPolicy.deferAutomaticSwitch(VoiceCallPolicy.MODE_IN_COMMUNICATION, false))
        assertTrue(VoiceCallPolicy.deferAutomaticSwitch(VoiceCallPolicy.MODE_NORMAL, true))
    }

    @Test fun callProtectionOverridesFastProfileAndUserTrafficToggle() {
        val plan = PowerPolicy.evaluate(
            PowerPolicy.Input(0, voiceCall = true),
            PowerPolicy.Options(profile = "fast", protectTraffic = false)
        )
        assertFalse(plan.allowLight)
        assertTrue(plan.deferTraffic)
        assertTrue(plan.voiceCallActive)
        assertEquals(1, plan.realParallel)
        assertEquals(120000L, plan.healthyMs)
        assertTrue(plan.reason.contains("звонок", ignoreCase = true))
    }

    @Test fun chargeOnlyAndWifiOnlyActuallySuppressBackgroundWork() {
        val mobile = PowerPolicy.evaluate(
            PowerPolicy.Input(0, metered = true),
            PowerPolicy.Options(profile = "fast", wifiOnly = true)
        )
        val unplugged = PowerPolicy.evaluate(
            PowerPolicy.Input(0, charging = false),
            PowerPolicy.Options(chargeOnly = true)
        )
        assertFalse(mobile.allowLight)
        assertFalse(unplugged.allowLight)
    }
}
