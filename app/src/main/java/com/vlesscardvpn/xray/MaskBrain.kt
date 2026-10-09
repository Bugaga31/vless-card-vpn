package com.vlesscardvpn.xray

import com.vlesscardvpn.model.Server
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * «Нейросеть масок»: a small neural network that lives on the phone and learns from every mask probe which tricks pass
 * on which network and server type. Input: what the mask does (fingerprint, fragments, ladder, pauses, DPI bypass, noise,
 * white SNI, ALPN, MSS, mux, TFO, IPv6) + the network (Wi-Fi / operator) + the server (REALITY / TLS / UDP / transport).
 * One hidden layer (16 tanh neurons) → probability that the mask works there.
 *
 * Trained online (SGD, a few passes over a replay of the last [MAX_SAMPLES] results, so old networks are not forgotten),
 * nothing leaves the phone. Used to order the mask search (likely ones first) and to pick which evolution mutants are
 * worth a probe. [accuracy]: how often it guessed right on new results BEFORE learning from them (honest, not training fit).
 */
class MaskBrain(seed: Int = 7) {
    private val h = HIDDEN
    private var w1 = Array(h) { DoubleArray(D) }
    private var b1 = DoubleArray(h)
    private var w2 = DoubleArray(h)
    private var b2 = 0.0
    private val replay = ArrayDeque<Pair<DoubleArray, Double>>()
    var samples = 0; private set
    /** Running share of right guesses on unseen results (0..1); -1 = not measured yet. */
    var accuracy = -1.0; private set

    init { val r = Random(seed); val s = 1.0 / sqrt(D.toDouble()); for (i in 0 until h) for (j in 0 until D) w1[i][j] = (r.nextDouble() * 2 - 1) * s; for (i in 0 until h) w2[i] = (r.nextDouble() * 2 - 1) * 0.3 }

    val trained: Boolean get() = samples >= MIN_SAMPLES

    @Synchronized fun predict(x: DoubleArray): Double {
        var o = b2
        for (i in 0 until h) { var a = b1[i]; val wi = w1[i]; for (j in 0 until D) a += wi[j] * x[j]; o += w2[i] * kotlin.math.tanh(a) }
        return 1 / (1 + exp(-o))
    }

    fun predict(m: Mask, net: String, s: Server?): Double = predict(features(m, net, s))

    private fun step(x: DoubleArray, y: Double, lr: Double) {
        val hid = DoubleArray(h); var o = b2
        for (i in 0 until h) { var a = b1[i]; for (j in 0 until D) a += w1[i][j] * x[j]; hid[i] = kotlin.math.tanh(a); o += w2[i] * hid[i] }
        val p = 1 / (1 + exp(-o)); val g = p - y   // d(log loss)/d(o)
        for (i in 0 until h) {
            val gh = g * w2[i] * (1 - hid[i] * hid[i])
            w2[i] -= lr * (g * hid[i] + L2 * w2[i])
            b1[i] -= lr * gh
            val wi = w1[i]; for (j in 0 until D) if (x[j] != 0.0) wi[j] -= lr * (gh * x[j] + L2 * wi[j])
        }
        b2 -= lr * g
    }

    /** New results (features → worked?): first scored (accuracy), then learned together with the replay. */
    @Synchronized fun learn(batch: List<Pair<DoubleArray, Boolean>>, passes: Int = 4, rnd: Random = Random(samples)) {
        if (batch.isEmpty()) return
        if (samples >= MIN_SAMPLES) {
            val right = batch.count { (x, y) -> (predict(x) >= 0.5) == y }.toDouble() / batch.size
            accuracy = if (accuracy < 0) right else accuracy * 0.7 + right * 0.3
        }
        batch.forEach { (x, y) -> replay.addLast(x to if (y) 1.0 else 0.0); if (replay.size > MAX_SAMPLES) replay.removeFirst() }
        samples += batch.size
        // passes over new results + an equal share of old ones (replay): learns the new network without forgetting the rest
        val fresh = batch.map { (x, y) -> x to if (y) 1.0 else 0.0 }
        val old = replay.toList().let { l -> if (l.size <= fresh.size) emptyList() else List(minOf(l.size, fresh.size * 2)) { l[rnd.nextInt(l.size)] } }
        val lr = 0.08 / (1 + samples / 4000.0)
        repeat(passes) { (fresh + old).shuffled(rnd).forEach { (x, y) -> step(x, y, lr) } }
    }

    fun learn(results: List<Triple<Mask, Server?, Boolean>>, net: String) = learn(results.map { (m, s, ok) -> features(m, net, s) to ok })

    /** Log loss on [data] (tests). */
    @Synchronized fun loss(data: List<Pair<DoubleArray, Boolean>>): Double =
        data.sumOf { (x, y) -> val p = predict(x).coerceIn(1e-6, 1 - 1e-6); -(if (y) ln(p) else ln(1 - p)) } / data.size.coerceAtLeast(1)

    @Synchronized fun toJson(): JSONObject = JSONObject().put("v", VERSION).put("samples", samples).put("acc", accuracy).put("b2", b2)
        .put("w1", JSONArray().apply { w1.forEach { r -> put(JSONArray().apply { r.forEach { put(round(it)) } }) } })
        .put("b1", JSONArray().apply { b1.forEach { put(round(it)) } }).put("w2", JSONArray().apply { w2.forEach { put(round(it)) } })
        .put("replay", JSONArray().apply { replay.forEach { (x, y) -> put(enc(x, y)) } })

    companion object {
        const val VERSION = 1
        const val HIDDEN = 16
        const val MIN_SAMPLES = 40
        const val MAX_SAMPLES = 1500
        private const val L2 = 1e-4
        private val FPS = listOf("", "chrome", "firefox", "safari", "edge", "ios", "android", "qq")
        private const val OPS = 8
        val D = FPS.size + 6 + 3 + 5 + 6 + 3 + OPS + 4

        private fun round(v: Double) = Math.round(v * 10000) / 10000.0
        private fun maxNum(s: String) = Regex("\\d+").findAll(s).maxOfOrNull { it.value.toIntOrNull() ?: 0 } ?: 0
        /** Replay sample as text: binary features as 0/1, the 2 graded ones with 2 digits, the label last. */
        private fun enc(x: DoubleArray, y: Double) = x.joinToString(",") { if (it == 0.0) "0" else if (it == 1.0) "1" else "%.2f".format(java.util.Locale.US, it) } + ";" + y.toInt()
        private fun dec(s: String): Pair<DoubleArray, Double>? = runCatching {
            val (a, b) = s.split(";"); val x = a.split(",").map { it.toDouble() }.toDoubleArray(); if (x.size != D) null else x to b.toDouble()
        }.getOrNull()

        fun features(m: Mask, net: String, s: Server?): DoubleArray {
            val x = DoubleArray(D); var k = 0
            x[k + FPS.indexOf(m.fingerprint).coerceAtLeast(0)] = 1.0; k += FPS.size
            x[k++] = if (m.packets.isNotEmpty()) 1.0 else 0.0
            x[k++] = if (m.packets == "tlshello") 1.0 else 0.0
            x[k++] = if (m.lengths.isNotEmpty()) 1.0 else 0.0
            x[k++] = (maxNum(m.length + "," + m.lengths) / 200.0).coerceAtMost(1.0)
            x[k++] = (maxNum(m.delay + "," + m.delays) / 50.0).coerceAtMost(1.0)
            x[k++] = if (m.maxSplit.isNotEmpty()) 1.0 else 0.0
            x[k++] = if (m.dpi == Masks.CURRENT_DPI) 1.0 else 0.0
            x[k++] = if (m.dpi.startsWith("TPWS#")) 1.0 else 0.0
            x[k++] = if (m.dpi.isNotEmpty() && m.dpi != Masks.CURRENT_DPI && !m.dpi.startsWith("TPWS#")) 1.0 else 0.0
            x[k++] = if (m.hasNoise) 1.0 else 0.0
            x[k++] = if (m.hop.isNotEmpty()) 1.0 else 0.0
            x[k++] = if (m.sni.isNotEmpty()) 1.0 else 0.0
            x[k++] = if (m.alpn.startsWith("h2")) 1.0 else 0.0
            x[k++] = if (m.alpn == "http/1.1") 1.0 else 0.0
            x[k++] = if (m.mss > 0) 1.0 else 0.0
            x[k++] = if (m.mss in 1..400) 1.0 else 0.0
            x[k++] = if (m.mss in 1..200) 1.0 else 0.0
            x[k++] = if (m.mux) 1.0 else 0.0
            x[k++] = if (m.tfo) 1.0 else 0.0
            x[k++] = if (m.ipv6) 1.0 else 0.0
            val wifi = net.startsWith("Wi-Fi"); val mob = net.startsWith("Моб")
            x[k++] = if (wifi) 1.0 else 0.0; x[k++] = if (mob) 1.0 else 0.0; x[k++] = if (!wifi && !mob) 1.0 else 0.0
            // the provider / operator (each has its own TSPU settings): a hashed slot
            x[k + Math.floorMod(net.hashCode(), OPS)] = 1.0; k += OPS
            if (s != null) {
                x[k] = if (s.security == "reality") 1.0 else 0.0
                x[k + 1] = if (s.security == "tls") 1.0 else 0.0
                x[k + 2] = if (s.protocol == "hysteria2" || s.protocol == "wireguard") 1.0 else 0.0
                x[k + 3] = if (s.network != "tcp") 1.0 else 0.0
            }
            return x
        }

        fun fromJson(o: JSONObject): MaskBrain = MaskBrain().apply {
            if (o.optInt("v") != VERSION) return@apply
            val a = o.optJSONArray("w1") ?: return@apply
            if (a.length() != HIDDEN || a.getJSONArray(0).length() != D) return@apply
            w1 = Array(HIDDEN) { i -> a.getJSONArray(i).let { r -> DoubleArray(D) { r.getDouble(it) } } }
            b1 = o.getJSONArray("b1").let { r -> DoubleArray(HIDDEN) { r.getDouble(it) } }
            w2 = o.getJSONArray("w2").let { r -> DoubleArray(HIDDEN) { r.getDouble(it) } }
            b2 = o.optDouble("b2", 0.0); samples = o.optInt("samples"); accuracy = o.optDouble("acc", -1.0)
            o.optJSONArray("replay")?.let { r -> for (i in 0 until r.length()) dec(r.getString(i))?.let { replay.addLast(it) } }
        }

        // ---- the one brain of the app (file in the app's private folder)
        @Volatile private var file: File? = null
        @Volatile var shared: MaskBrain = MaskBrain(); private set
        fun init(dir: File) {
            if (file != null) return
            file = File(dir, "mask_brain.json")
            shared = runCatching { fromJson(JSONObject(file!!.readText())) }.getOrDefault(MaskBrain())
        }
        fun save() { val f = file ?: return; runCatching { val t = File(f.path + ".tmp"); t.writeText(shared.toJson().toString()); t.renameTo(f) } }
        fun reset() { shared = MaskBrain(); file?.delete() }
        fun summary(): String = shared.let { b ->
            if (b.samples == 0) "ещё не обучалась — начнёт с первого «Подобрать маскировку»"
            else "обучена на ${b.samples} проверках" + if (b.accuracy >= 0) ", угадывает ${(b.accuracy * 100).toInt()}% новых результатов" else ", пока учится (нужно ≥ $MIN_SAMPLES)"
        }
    }
}
