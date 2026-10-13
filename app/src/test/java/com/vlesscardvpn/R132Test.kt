package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Base64
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class R132Test {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun srv(i: Int = 1) = Server(name = "s$i", protocol = "vless", address = "1.2.3.$i", port = 443, secret = "secret")
    private fun model(v: Double) = JSONObject().put("m", JSONArray(List(Brain.N) { v }))
    private fun withFreshBrain(f: () -> Unit) {
        val old = Brain.snapshot()
        try { Brain.clear(); f() } finally { assertTrue(Brain.restore(old)) }
    }

    @Test fun authenticatedEncryptionRoundTripAndRandomNonces() {
        val k = key(); val a = SecretEnvelope.seal(k, "state", "секреты: password")
        val b = SecretEnvelope.seal(k, "state", "секреты: password")
        assertEquals("секреты: password", SecretEnvelope.open(k, "state", a))
        assertFalse(a.contentEquals(b))
        assertFalse(String(a).contains("password"))
        assertTrue(a.contentEquals(Base64.decode(Base64.encode(a))))
        assertEquals("", SecretEnvelope.open(k, "state", SecretEnvelope.seal(k, "state", "")))
    }
    @Test fun tamperingWrongKeyWrongPurposeAndTruncationAreRejected() {
        val k = key(); val a = SecretEnvelope.seal(k, "state", "secret")
        val bad = a.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { SecretEnvelope.open(k, "state", bad) }.isFailure)
        assertTrue(runCatching { SecretEnvelope.open(key(), "state", a) }.isFailure)
        assertTrue(runCatching { SecretEnvelope.open(k, "github-token", a) }.isFailure)
        assertTrue(runCatching { SecretEnvelope.open(k, "state", a.copyOf(12)) }.isFailure)
        assertTrue(runCatching { SecretEnvelope.open(k, "state", a.clone().also { it[3] = 2 }) }.isFailure)
    }
    @Test fun boundedReadersAndDecompressionRejectOversizeInput() {
        assertArrayEquals(byteArrayOf(1, 2, 3), BoundedInput.read(byteArrayOf(1, 2, 3).inputStream(), 3))
        assertTrue(runCatching { BoundedInput.read(ByteArray(4).inputStream(), 3) }.isFailure)
        val st = AppState(servers = listOf(srv().copy(source = "manual")))
        assertEquals(st.servers, Backup.unpack(Backup.find(Backup.pack(st))!!)!!.servers)
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(ByteArray(8 * 1024 * 1024 + 1) { 32 }) }
        assertNull(Backup.unpack(Base64.encode(bos.toByteArray())))
        assertNull(Backup.unpack("a".repeat(3_000_001)))
    }
    @Test fun publicUnknownAndMalformedRepositoriesCannotReceiveKeys() {
        assertTrue(CloudSync.permitsBackup("""{"private":true}"""))
        assertFalse(CloudSync.permitsBackup("""{"private":false}"""))
        assertFalse(CloudSync.permitsBackup("""{"private":"true"}"""))
        assertFalse(CloudSync.permitsBackup("{}"))
        assertFalse(CloudSync.permitsBackup("bad"))
    }
    @Test fun modelImportRejectsInvalidWeightsWithoutChangingAnything() = withFreshBrain {
        Brain.learn("Моб.: A", srv(), ServerState(tcpMs = 100), true)
        val old = Brain.snapshot()
        assertFalse(Brain.restore(model(9.0).toString()))
        assertEquals(old, Brain.snapshot())
        assertFalse(Brain.restore(JSONObject().put("m", JSONArray(List(Brain.N) { "NaN" })).toString()))
        assertFalse(Brain.restore(JSONObject().put("m", JSONArray(listOf(1))).toString()))
        assertFalse(Brain.restore("bad"))
        assertEquals(old, Brain.snapshot())
        Brain.blend(model(9.0), 100)
        assertEquals(old, Brain.snapshot())
        assertTrue(Brain.p("Моб.: A", srv(), ServerState()) in 0.0..1.0)
    }
    @Test fun mobileExperienceDoesNotSlowDownNewWifiLearning() = withFreshBrain {
        val s = srv(); val st = ServerState(tcpMs = 100)
        repeat(100) { Brain.learn("Моб.: A", s, st, true) }
        Brain.learn("Wi-Fi", s, st, false)
        val counts = JSONObject(Brain.snapshot()).getJSONObject("trained")
        assertEquals(100.0, counts.getDouble("m"), 1e-9)
        assertEquals(1.0, counts.getDouble("w"), 1e-9)
        assertTrue(Brain.learningRate(counts.getDouble("w")) > Brain.learningRate(counts.getDouble("m")))
        val saved = Brain.snapshot(); Brain.clear(); assertTrue(Brain.restore(saved))
        assertEquals(counts.toString(), JSONObject(Brain.snapshot()).getJSONObject("trained").toString())
    }
    @Test fun repeatedCloudModelDoesNotRepeatedlyOverwriteLocalLearning() = withFreshBrain {
        repeat(100) { Brain.learn("Моб.: A", srv(), ServerState(tcpMs = 100), true) }
        val before = JSONObject(Brain.snapshot()).getJSONArray("m")
        Brain.blend(model(8.0), 1000)
        val after = JSONObject(Brain.snapshot()).getJSONArray("m")
        (0 until Brain.N).forEach { assertTrue(kotlin.math.abs(after.getDouble(it) - before.getDouble(it)) <= 1.6) }
        val once = Brain.snapshot()
        repeat(20) { Brain.blend(model(8.0), 1000) }
        assertEquals(once, Brain.snapshot())
    }
    @Test fun aggregateTunnelResultDoesNotLabelEveryBalancerServer() = withFreshBrain {
        val st = ServerState(tcpMs = 100)
        Brain.lived("Wi-Fi", listOf(srv(1), srv(2)), { st }, false)
        assertEquals(0, Brain.seen)
        Brain.lived("Wi-Fi", listOf(srv(1)), { st }, true)
        assertEquals(1, Brain.seen)
        assertEquals(0.2, JSONObject(Brain.snapshot()).getJSONObject("trained").getDouble("w"), 1e-9)
    }
    @Test fun featureCountersCannotOverflowAndOldModelsMigrate() = withFreshBrain {
        val s = srv(); val st = ServerState(okCount = Int.MAX_VALUE, failCount = Int.MAX_VALUE)
        assertEquals(0.5, Brain.features(s, st)[2], 1e-9)
        assertTrue(Brain.features(s, st).all { it.isFinite() })
        assertTrue(Brain.restore(model(0.0).put("seen", 600).toString()))
        assertEquals(600.0, JSONObject(Brain.snapshot()).getJSONObject("trained").getDouble("m"), 1e-9)
        Brain.clear(); repeat(2) { Brain.learn("Моб.: A", s, st, true) }
        val modern = Brain.snapshot(); Brain.clear(); Brain.restore(modern)
        Brain.learn("Wi-Fi", s, st, true)
        assertEquals(1.0, JSONObject(Brain.snapshot()).getJSONObject("trained").getDouble("w"), 1e-9)
    }
}
