package com.vlesscardvpn

import com.vlesscardvpn.core.DeviceAdvice as D
import com.vlesscardvpn.core.DeviceSignals
import com.vlesscardvpn.core.PhoneLearning
import org.junit.Assert.*
import org.junit.Test

class R135Test {
    private fun snapshot(signals: DeviceSignals = DeviceSignals(0, temp = 30.0, thermal = 0, memoryUsed = .7)) =
        D.Snapshot(signals, 2_000_000_000, 8_000_000_000, 30_000_000_000, 64_000_000_000, 80, false)
    @Test fun cachedRamIsNotAutomaticallyDeclaredAProblem() {
        val tips = D.tips(snapshot(DeviceSignals(0, memoryUsed = .97, lowMemory = false)), false)
        assertFalse(tips.any { it.text.contains("сообщил о нехватке памяти") })
        assertTrue(tips.first().text.contains("ускорение не обещается"))
    }
    @Test fun actualMemoryPressureSuggestsManualAppManagement() {
        val tips = D.tips(snapshot(DeviceSignals(0, lowMemory = true)), false)
        assertTrue(tips.any { it.action == "apps" && it.text.contains("вручную") })
        assertFalse(tips.any { it.text.contains("очищена") })
    }
    @Test fun batteryTemperatureAndChargingAreUsedWithoutCpuClaims() {
        val tips = D.tips(snapshot(DeviceSignals(0, temp = 39.5, charging = true)), false)
        assertTrue(tips.any { it.action == "display" })
        assertFalse(D.tips(snapshot(DeviceSignals(0, temp = 39.5, charging = false)), false).any { it.action == "display" })
    }
    @Test fun unknownSensorsAndStorageNeverInventAProblem() {
        val s = D.Snapshot(DeviceSignals(0), 0, 0, 0, 0, -1, false)
        assertEquals(1, D.tips(s, false).size)
        assertFalse(D.tips(s, false).any { it.action == "storage" || it.action == "battery" || it.action == "display" })
    }
    @Test fun storageWarningsRequireRealFreeBytesAndAreManual() {
        val s = snapshot().copy(storageAvailable = 500_000_000)
        assertTrue(D.tips(s, false).any { it.action == "storage" && it.text.contains("не удаляется автоматически") })
        assertFalse(D.tips(snapshot(), false).any { it.action == "storage" })
    }
    @Test fun forecastIsClearlyDifferentFromObservedOverheating() {
        val tips = D.tips(snapshot(), true)
        assertTrue(tips.any { it.text.contains("прогноз, не измерение") })
        assertFalse(tips.any { it.text.startsWith("Телефон нагрет") })
    }
    @Test fun neuralFeaturesDescribeWholePhoneRatherThanVpnState() {
        val low = PhoneLearning.features(DeviceSignals(0, temp = 30.0, memoryUsed = .2), 30.0)
        val high = PhoneLearning.features(DeviceSignals(0, temp = 30.0, memoryUsed = .9), 30.0)
        assertTrue(high[4] > low[4])
        assertArrayEquals(low.copyOfRange(0, 4), high.copyOfRange(0, 4), 0.0)
        assertTrue(PhoneLearning.features(DeviceSignals(0, temp = 100.0, memoryUsed = -1.0), 0.0).all { it.isFinite() && it in -1.0..1.0 })
    }
    @Test fun encryptedModelIsPurposeBoundAndTamperingFails() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val m = PhoneLearning().apply { repeat(5) { train(doubleArrayOf(.1, .2, .3, .4, .5), it % 2 == 0) } }
        val blob = com.vlesscardvpn.core.SecretEnvelope.seal(key, "phone-model-v1", m.encode())
        assertEquals(m.encode(), com.vlesscardvpn.core.SecretEnvelope.open(key, "phone-model-v1", blob))
        assertTrue(runCatching { com.vlesscardvpn.core.SecretEnvelope.open(key, "state", blob) }.isFailure)
        blob[blob.lastIndex] = (blob.last().toInt() xor 1).toByte()
        assertTrue(runCatching { com.vlesscardvpn.core.SecretEnvelope.open(key, "phone-model-v1", blob) }.isFailure)
    }
    @Test fun invalidRestoreDoesNotPartiallyChangeAValidModel() {
        val m = PhoneLearning(); val before = m.encode()
        val bad = before.split(',').toMutableList().apply { this[31] = "Infinity" }.joinToString(",")
        assertTrue(runCatching { m.restore(bad) }.isFailure)
        assertEquals(before, m.encode())
    }

}
