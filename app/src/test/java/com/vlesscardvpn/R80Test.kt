package com.vlesscardvpn

import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.MaskBrain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class R80Test {
    private fun mask(r: Random, i: Int) = Mask("t$i", "t", listOf("chrome", "firefox", "safari", "").random(r),
        packets = if (r.nextBoolean()) "tlshello" else "", length = if (r.nextBoolean()) "${r.nextInt(1, 200)}" else "",
        mux = r.nextInt(3) == 0, mss = if (r.nextInt(4) == 0) 300 else 0, sni = if (r.nextInt(4) == 0) "vk.com" else "")

    /** A made-up TSPU: on MTS only fragmented hellos pass; on the home Wi-Fi everything except mux. */
    private fun truth(m: Mask, net: String) = if (net.startsWith("Моб")) m.packets.isNotEmpty() else !m.mux
    private fun data(n: Int, seed: Int): List<Pair<DoubleArray, Boolean>> { val r = Random(seed)
        return List(n) { i -> val m = mask(r, i); val net = if (r.nextBoolean()) "Моб.: MTS" else "Wi-Fi · 192.168.1.1"; MaskBrain.features(m, net, null) to truth(m, net) } }

    @Test fun learnsPerNetworkRules() {
        val b = MaskBrain()
        val test = data(300, 99)
        val before = b.loss(test)
        data(800, 1).chunked(40).forEach { b.learn(it) }
        val acc = test.count { (x, y) -> (b.predict(x) >= 0.5) == y }.toDouble() / test.size
        assertTrue("accuracy $acc", acc > 0.9)
        assertTrue(b.loss(test) < before)
        assertTrue(b.accuracy > 0.8)   // measured on batches before learning them
    }

    @Test fun savedBrainPredictsTheSame() {
        val b = MaskBrain(); data(200, 3).chunked(50).forEach { b.learn(it) }
        val c = MaskBrain.fromJson(JSONObject(b.toJson().toString()))
        val x = data(5, 7)
        x.forEach { (f, _) -> assertEquals(b.predict(f), c.predict(f), 1e-3) }
        assertEquals(b.samples, c.samples)
        assertFalse(MaskBrain.fromJson(JSONObject("{\"v\":0}")).trained)
    }

    @Test fun settingRoundTrip() {
        assertFalse(Settings.fromJson(JSONObject(Settings(maskBrain = false).toJson().toString())).maskBrain)
        assertTrue(Settings.fromJson(JSONObject("{}")).maskBrain)
    }
}
