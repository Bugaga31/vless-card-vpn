package com.vlesscardvpn.core

/** User target: the upper bound is a gate; faster-than-target replies are welcome. */
object AutoLatencyRange {
    const val DEFAULT_MAX = 3000
    const val PRESET_VERSION = 227
    const val DEFAULT_MIN = 100
    const val LIMIT = 3000
    data class Range(val min: Int = DEFAULT_MIN, val max: Int = DEFAULT_MAX) {
        fun valid() = min in 1..LIMIT && max in min..LIMIT
        fun accepts(works: Boolean, https: Int, telegram: Boolean?, tg: Int) =
            valid() && works && telegram == true && https in 1..max && tg in 1..max
    }
    fun parse(text: String): Range? {
        val match = Regex("""\s*(\d{1,4})(?:\s*[-–—]\s*(\d{1,4}))?\s*""").matchEntire(text) ?: return null
        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        return (if (second == null) Range(1, first) else Range(first, second)).takeIf { it.valid() }
    }
    fun safe(min: Int, max: Int) = Range(min, max).takeIf { it.valid() } ?: Range()
    fun text(range: Range) = if (range.min == 1) "${range.max}" else "${range.min}–${range.max}"
}
