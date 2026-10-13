package com.vlesscardvpn.core

import kotlin.math.PI
import kotlin.math.sin

/** Geometry and animation eligibility are pure; background drawing never touches the network. */
object OceanPolicy {
    const val FRAME_MS = 50L // at most 20 redraws/s, not an uncapped 60/120 Hz transition
    fun animate(motion: Boolean, foreground: Boolean, saver: Boolean, battery: Int, charging: Boolean,
                temp: Double, thermal: Int, systemScale: Float): Boolean = motion && foreground && !saver &&
        (charging || battery !in 1..20) && temp < (if (charging) 39.0 else 41.0) && thermal < 2 && systemScale > 0
    fun y(x: Double, seconds: Double, layer: Int): Double {
        val l = layer.coerceIn(0, 3)
        val phase = seconds * 2 * PI / (28 + l * 8)
        return .56 + l * .10 + sin(x * 2 * PI * (1.1 + l * .12) + phase) * (.018 + l * .002) +
            sin(x * 4 * PI - phase * .45 + l) * .006
    }
}
