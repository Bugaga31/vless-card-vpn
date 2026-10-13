package com.vlesscardvpn.core

import kotlin.math.exp
import kotlin.math.tanh

/** Tiny 5→4→1 neural network, bounded online gradient descent. No Android or network dependencies. */
class PhoneLearning {
    var samples = 0; private set
    var positives = 0; private set
    var negatives = 0; private set
    private val weights = DoubleArray(29) { i -> ((i * 17 % 23) - 11) / 60.0 }
    private fun hidden(x: DoubleArray) = DoubleArray(4) { j -> tanh(weights[j * 6 + 5] + (0..4).sumOf { k -> weights[j * 6 + k] * x[k].coerceIn(-1.0, 1.0) }) }
    fun predict(x: DoubleArray): Double {
        require(x.size == 5 && x.all { it.isFinite() })
        val h = hidden(x)
        return 1.0 / (1.0 + exp(-(weights[28] + (0..3).sumOf { weights[24 + it] * h[it] }).coerceIn(-20.0, 20.0)))
    }
    fun train(x: DoubleArray, hotLater: Boolean) {
        val p = predict(x); val h = hidden(x); val error = p - if (hotLater) 1.0 else 0.0
        val oldOutput = weights.copyOfRange(24, 28)
        for (j in 0..3) {
            val d = error * oldOutput[j] * (1 - h[j] * h[j])
            for (k in 0..4) weights[j * 6 + k] = (weights[j * 6 + k] - .03 * d * x[k].coerceIn(-1.0, 1.0)).coerceIn(-4.0, 4.0)
            weights[j * 6 + 5] = (weights[j * 6 + 5] - .03 * d).coerceIn(-4.0, 4.0)
            weights[24 + j] = (weights[24 + j] - .03 * error * h[j]).coerceIn(-4.0, 4.0)
        }
        weights[28] = (weights[28] - .03 * error).coerceIn(-4.0, 4.0)
        samples = (samples + 1).coerceAtMost(1000000)
        if (hotLater) positives = (positives + 1).coerceAtMost(1000000) else negatives = (negatives + 1).coerceAtMost(1000000)
    }
    fun ready() = samples >= 30 && positives >= 3 && negatives >= 3
    fun encode() = (listOf(samples.toString(), positives.toString(), negatives.toString()) + weights.map { it.toString() }).joinToString(",")
    fun restore(text: String) {
        val v = text.split(','); require(v.size == 32)
        val counts = v.take(3).map { it.toInt().also { n -> require(n in 0..1000000) } }
        val w = v.drop(3).map { it.toDouble().also { n -> require(n.isFinite() && n in -4.0..4.0) } }
        require(counts[1] + counts[2] >= counts[0])
        samples = counts[0]; positives = counts[1]; negatives = counts[2]
        w.forEachIndexed { i, n -> weights[i] = n }
    }
    companion object {
        fun features(i: DeviceSignals, previousTemp: Double) = doubleArrayOf(
            ((i.temp - 30) / 15).coerceIn(-1.0, 1.0), ((i.temp - previousTemp) / 3).coerceIn(-1.0, 1.0),
            if (i.charging) 1.0 else -1.0, if (i.activeTraffic) 1.0 else -1.0, if (i.memoryUsed >= 0) (i.memoryUsed * 2 - 1).coerceIn(-1.0, 1.0) else 0.0)
    }
}
