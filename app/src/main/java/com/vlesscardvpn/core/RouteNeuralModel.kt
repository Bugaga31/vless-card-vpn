package com.vlesscardvpn.core

import kotlin.math.exp
import kotlin.math.tanh

/** Experimental 12→8→1 MLP. Predicts a full probe outcome, never manufactures connectivity proof.
 * Inputs are captured BEFORE the probe. No endpoints, identifiers, payload or service results as features.
 */
class RouteNeuralModel {
    companion object { const val INPUTS = 12; const val HIDDEN = 8; const val WEIGHTS = 113 }
    private var weights = DoubleArray(WEIGHTS) { i -> ((i * 37 % 101) - 50) / 500.0 }
    var positives = 0; private set
    var negatives = 0; private set
    val samples get() = positives + negatives
    fun ready() = samples >= 40 && positives >= 8 && negatives >= 8
    private fun check(x: DoubleArray) { require(x.size == INPUTS && x.all { it.isFinite() && it in -1.0..1.0 }) }
    private fun hidden(x: DoubleArray) = DoubleArray(HIDDEN) { j ->
        tanh(weights[j * 13 + 12] + (0 until INPUTS).sumOf { k -> weights[j * 13 + k] * x[k] })
    }
    fun predict(x: DoubleArray): Double {
        check(x); val h = hidden(x)
        val z = weights[112] + (0 until HIDDEN).sumOf { weights[104 + it] * h[it] }
        return 1.0 / (1.0 + exp(-z.coerceIn(-20.0, 20.0)))
    }
    fun train(x: DoubleArray, success: Boolean) {
        check(x)
        val h = hidden(x); val error = predict(x) - if (success) 1.0 else 0.0
        val output = weights.copyOfRange(104, 112)
        fun update(i: Int, gradient: Double) { weights[i] = (weights[i] - .025 * (gradient + .001 * weights[i])).coerceIn(-4.0, 4.0) }
        for (j in 0 until HIDDEN) {
            val delta = error * output[j] * (1.0 - h[j] * h[j])
            for (k in 0 until INPUTS) update(j * 13 + k, delta * x[k])
            update(j * 13 + 12, delta); update(104 + j, error * h[j])
        }
        update(112, error)
        if (success) positives = (positives + 1).coerceAtMost(500_000) else negatives = (negatives + 1).coerceAtMost(500_000)
    }
    fun encode() = (listOf("1", positives.toString(), negatives.toString()) + weights.map { it.toString() }).joinToString(",")
    /** Validate in temporary storage: malformed imports cannot partially change a live model. */
    fun restore(text: String): Boolean = runCatching {
        require(text.length <= 8192)
        val values = text.split(','); require(values.size == WEIGHTS + 3 && values[0] == "1")
        val pos = values[1].toInt(); val neg = values[2].toInt(); require(pos in 0..500_000 && neg in 0..500_000)
        val next = values.drop(3).map { it.toDouble().also { n -> require(n.isFinite() && n in -4.0..4.0) } }.toDoubleArray()
        weights = next; positives = pos; negatives = neg; true
    }.getOrDefault(false)
}
