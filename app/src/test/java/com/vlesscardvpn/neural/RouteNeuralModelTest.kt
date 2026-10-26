package com.vlesscardvpn.neural

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.Masks
import org.junit.Assert.*
import org.junit.Test

class RouteNeuralModelTest {
    private val x = DoubleArray(12) { if (it % 2 == 0) .8 else -.6 }
    @Test fun learnsBothOutcomesWithoutConstantSaturation() {
        val m = RouteNeuralModel(); val opposite = DoubleArray(12) { -x[it] }
        repeat(600) { m.train(x, true); m.train(opposite, false) }
        assertTrue(m.ready()); assertTrue(m.predict(x) > .8); assertTrue(m.predict(opposite) < .2)
    }
    @Test fun noAuthorityBeforeBalancedMinimum() {
        val m = RouteNeuralModel(); repeat(80) { m.train(x, true) }; assertFalse(m.ready())
        repeat(8) { m.train(x, false) }; assertTrue(m.ready())
    }
    @Test fun deterministicRoundTrip() {
        val a = RouteNeuralModel(); repeat(50) { a.train(x, it % 2 == 0) }
        val b = RouteNeuralModel(); assertTrue(b.restore(a.encode()))
        assertEquals(a.predict(x), b.predict(x), 0.0); assertEquals(a.samples, b.samples)
    }
    @Test fun malformedImportIsAtomic() {
        val m = RouteNeuralModel(); m.train(x, true); val old = m.encode()
        for (bad in listOf("", "2," + old.substringAfter(','), old.replaceFirst(",", ",NaN,"), old + ",0", "a".repeat(8193))) {
            assertFalse(m.restore(bad)); assertEquals(old, m.encode())
        }
        val parts = old.split(',').toMutableList(); parts[5] = "NaN"
        assertFalse(m.restore(parts.joinToString(","))); assertEquals(old, m.encode())
    }
    @Test fun invalidInputsCannotPoisonWeights() {
        val m = RouteNeuralModel(); val old = m.encode()
        for (bad in listOf(DoubleArray(11), x.copyOf().also { it[0] = Double.NaN }, x.copyOf().also { it[0] = 2.0 })) {
            try { m.train(bad, true); fail("invalid input accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(old, m.encode())
    }
    @Test fun unknownAndMissingTransferAreNotLabels() {
        assertNull(NeuralRouting.label(Probe(100, true, null, tgOk = null, bodyBytes = 262144)))
        assertNull(NeuralRouting.label(Probe(100, true, true, tgOk = true)))
        assertNull(NeuralRouting.label(Tester.SKIPPED))
        assertNull(NeuralRouting.label(Probe(0, false, false, tgOk = false)))
    }
    @Test fun fullServicePassAndExplicitServiceFailureHaveIndependentLabels() {
        val p = Probe(100, true, true, tgOk = true, bodyBytes = TransferProof.REQUIRED_BYTES.toLong())
        assertEquals(true, NeuralRouting.label(p)); assertEquals(false, NeuralRouting.label(p.copy(tgOk = false)))
        assertEquals(false, NeuralRouting.label(p.copy(ytOk = false)))
        assertNull(NeuralRouting.label(p.copy(bigOk = null)))
    }
    private val server = Server(name = "fixture", protocol = "vless", address = "fixture.test", port = 443, secret = "fixture")
    @Test fun featuresDoNotUseOtherNetworkEvidenceOrEndpointIdentifiers() {
        val state = ServerState(tcpMs = 5, realMs = 20, kbps = 9999, tgOk = true, ytOk = true,
            netEvidence = mapOf("Wi-Fi · old|" to RouteEvidence(20, 19, latency = 42.0)))
        val a = NeuralRouting.features("Wi-Fi · new", server, state, null)
        val b = NeuralRouting.features("Wi-Fi · new", server.copy(name = "other", address = "other.test", secret = "other"), ServerState(), null)
        assertArrayEquals(a, b, 0.0)
    }
    @Test fun maskFeaturesStayBounded() {
        Masks.ALL.take(100).forEach { m ->
            val f = NeuralRouting.features("Моб. · fixture", server, ServerState(), m)
            assertEquals(12, f.size); assertTrue(f.all { it.isFinite() && it in -1.0..1.0 })
        }
    }
    @Test fun experimentDefaultsOffAndSettingsRoundTrip() {
        assertFalse(Settings().neuralRouting)
        assertTrue(Settings.fromJson(Settings(neuralRouting = true).toJson()).neuralRouting)
    }
    @Test fun changedOrReturnedNetworkCannotReuseOldTicket() {
        val ticket = NeuralRouting.Ticket("fixture", "101", "Wi-Fi · a", DoubleArray(12), 1)
        assertTrue(NeuralRouting.current(ticket, "Wi-Fi · a", "101", 1))
        assertFalse(NeuralRouting.current(ticket, "Wi-Fi · b", "102", 2))
        assertFalse(NeuralRouting.current(ticket, "Wi-Fi · a", "101", 3))
        assertFalse(NeuralRouting.current(ticket, "Wi-Fi · a", null, 1))
    }
    @Test fun authorityIsZeroUntilReadyAndBoundedAfterwards() {
        val m = RouteNeuralModel(); assertEquals(0.0, NeuralRouting.boundedAdjustment(m, x), 0.0)
        repeat(300) { m.train(x, true); m.train(DoubleArray(12) { -x[it] }, false) }
        val adjustment = NeuralRouting.boundedAdjustment(m, x)
        assertTrue(adjustment > 0.0 && adjustment <= .05)
        assertTrue(NeuralRouting.boundedAdjustment(m, DoubleArray(12) { -x[it] }) in -.05..0.0)
    }
    @Test fun disabledNeuralControllerCannotChangeRank() {
        assertEquals(0.0, NeuralRouting.adjustment("Wi-Fi", server, ServerState(), null), 0.0)
    }
}
